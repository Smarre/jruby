package org.jruby.compiler.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubyModule;
import org.jruby.compiler.ir.compiler_pass.CFGBuilder;
import org.jruby.compiler.ir.compiler_pass.CompilerPass;
import org.jruby.compiler.ir.compiler_pass.DominatorTreeBuilder;
import org.jruby.compiler.ir.compiler_pass.IRPrinter;
import org.jruby.compiler.ir.compiler_pass.InlineTest;
import org.jruby.compiler.ir.compiler_pass.LinearizeCFG;
import org.jruby.compiler.ir.compiler_pass.LiveVariableAnalysis;
import org.jruby.compiler.ir.compiler_pass.opts.DeadCodeElimination;
import org.jruby.compiler.ir.compiler_pass.opts.LocalOptimizationPass;
import org.jruby.compiler.ir.dataflow.DataFlowProblem;
import org.jruby.compiler.ir.instructions.CallBase;
import org.jruby.compiler.ir.instructions.CopyInstr;
import org.jruby.compiler.ir.instructions.Instr;
import org.jruby.compiler.ir.instructions.ReceiveClosureInstr;
import org.jruby.compiler.ir.instructions.ReceiveSelfInstr;
import org.jruby.compiler.ir.instructions.ResultInstr;
import org.jruby.compiler.ir.instructions.ThreadPollInstr;
import org.jruby.compiler.ir.instructions.ZSuperInstr;
import org.jruby.compiler.ir.operands.Label;
import org.jruby.compiler.ir.operands.LocalVariable;
import org.jruby.compiler.ir.operands.Operand;
import org.jruby.compiler.ir.operands.Self;
import org.jruby.compiler.ir.operands.TemporaryVariable;
import org.jruby.compiler.ir.operands.Variable;
import org.jruby.compiler.ir.operands.WrappedIRClosure;
import org.jruby.compiler.ir.representations.BasicBlock;
import org.jruby.compiler.ir.representations.CFG;
import org.jruby.compiler.ir.representations.CFGInliner;
import org.jruby.compiler.ir.representations.CFGLinearizer;
import org.jruby.parser.StaticScope;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;

/**
 * Right now, this class abstracts the following execution scopes:
 * Method, Closure, Module, Class, MetaClass
 * Top-level Script, and Eval Script
 *
 * In the compiler-land, IR versions of these scopes encapsulate only as much 
 * information as is required to convert Ruby code into equivalent Java code.
 *
 * But, in the non-compiler land, there will be a corresponding java object for
 * some of these scopes which encapsulates the runtime semantics and data needed
 * for implementing them.  In the case of Module, Class, MetaClass, and Method,
 * they also happen to be instances of the corresponding Ruby classes -- so,
 * in addition to providing code that help with this specific ruby implementation,
 * they also have code that let them behave as ruby instances of their corresponding
 * classes.
 *
 * Examples:
 * - the runtime class object might have refs. to the runtime method objects.
 * - the runtime method object might have a slot for a heap frame (for when it
 *   has closures that need access to the method's local variables), it might
 *   have version information, it might have references to other methods that
 *   were optimized with the current version number, etc.
 * - the runtime closure object will have a slot for a heap frame (for when it 
 *   has closures within) and might get reified as a method in the java land
 *   (but inaccessible in ruby land).  So, passing closures in Java land might
 *   be equivalent to passing around the method handles.
 *
 * and so on ...
 */
public abstract class IRScope {
    private static final Logger LOG = LoggerFactory.getLogger("IRScope");

    private static Integer globalScopeCount = 0;

    /** Unique global scope id */
    private int scopeId;

    /** Name */
    private String name;

    /** File within which this scope has been defined */
    private final String fileName;

    /** Starting line for this scope's definition */
    private final int lineNumber;

    /** Lexical parent scope */
    private IRScope lexicalParent;

    /** Parser static-scope that this IR scope corresponds to */
    private StaticScope staticScope;
    
    /** Live version of module within whose context this method executes */
    private RubyModule containerModule; 
    
    /** List of IR instructions for this method */
    private List<Instr> instrList; 

    /** Control flow graph representation of this method's instructions */
    private CFG cfg;

    /** List of (nested) closures in this scope */
    private List<IRClosure> closures;

    /** Local variables defined in this scope */
    private Set<Variable> definedLocalVars;

    /** Local variables used in this scope */
    private Set<Variable> usedLocalVars;

    /** Is %block implicit block arg unused? */
    private boolean hasUnusedImplicitBlockArg;

    /** Map of name -> dataflow problem */
    private Map<String, DataFlowProblem> dfProbs;
    
    private Instr[] linearizedInstrArray;
    private List<BasicBlock> linearizedBBList;
    protected int temporaryVariableIndex;

    /** Keeps track of types of prefix indexes for variables and labels */
    private Map<String, Integer> nextVarIndex;

    // Index values to guarantee we don't assign same internal index twice
    private int nextClosureIndex;
    
    protected static class LocalVariableAllocator {
        public int nextSlot;
        public Map<String, LocalVariable> varMap;

        public LocalVariableAllocator() {
            varMap = new HashMap<String, LocalVariable>();
            nextSlot = 0;
        }

        public final LocalVariable getVariable(String name) {
            return varMap.get(name);
        }

        public final void putVariable(String name, LocalVariable var) {
            varMap.put(name, var);
            nextSlot++;
        }
    }

    LocalVariableAllocator localVars;
    LocalVariableAllocator evalScopeVars;

    /* *****************************************************************************************************
     * Does this execution scope (applicable only to methods) receive a block and use it in such a way that
     * all of the caller's local variables need to be materialized into a heap binding?
     * Ex: 
     *    def foo(&b)
     *      eval 'puts a', b
     *    end
     *  
     *    def bar
     *      a = 1
     *      foo {} # prints out '1'
     *    end
     *
     * Here, 'foo' can access all of bar's variables because it captures the caller's closure.
     *
     * There are 2 scenarios when this can happen (even this is conservative -- but, good enough for now)
     * 1. This method receives an explicit block argument (in this case, the block can be stored, passed around,
     *    eval'ed against, called, etc.).  
     *    CAVEAT: This is conservative ... it may not actually be stored & passed around, evaled, called, ...
     * 2. This method has a 'super' call (ZSuper AST node -- ZSuperInstr IR instruction)
     *    In this case, the parent (in the inheritance hierarchy) can access the block and store it, etc.  So, in reality,
     *    rather than assume that the parent will always do this, we can query the parent, if we can precisely identify
     *    the parent method (which in the face of Ruby's dynamic hierarchy, we cannot).  So, be pessimistic.
     *
     * This logic was extracted from an email thread on the JRuby mailing list -- Yehuda Katz & Charles Nutter
     * contributed this analysis above.
     * ********************************************************************************************************/
    private boolean canCaptureCallersBinding;

    /* ****************************************************************************
     * Does this scope define code, i.e. does it (or anybody in the downward call chain)
     * do class_eval, module_eval? In the absence of any other information, we default
     * to yes -- which basically leads to pessimistic but safe optimizations.  But, for
     * library and internal methods, this might be false.
     * **************************************************************************** */
    private boolean canModifyCode;

    /* ****************************************************************************
     * Does this scope require a binding to be materialized?
     * Yes if any of the following holds true:
     * - calls 'Proc.new'
     * - calls 'eval'
     * - calls 'call' (could be a call on a stored block which could be local!)
     * - calls 'send' and we cannot resolve the message (method name) that is being sent!
     * - calls methods that can access the caller's binding
     * - calls a method which we cannot resolve now!
     * - has a call whose closure requires a binding
     * **************************************************************************** */
    private boolean bindingHasEscaped;

    /** Does this scope call any eval */
    private boolean usesEval;

    /** Does this scope call any zsuper */
    private boolean usesZSuper;

    /** Does this scope have loops? */
    private boolean hasLoops;

    /** # of thread poll instrs added to this scope */
    private int threadPollInstrsCount;
    
    private IRManager manager;

    // Used by cloning code
    protected IRScope(IRScope s, IRScope lexicalParent) {
        this.lexicalParent = lexicalParent;
        this.manager = s.manager;
        this.fileName = s.fileName;
        this.lineNumber = s.lineNumber;
        this.staticScope = s.staticScope;
        this.threadPollInstrsCount = s.threadPollInstrsCount;
        this.nextClosureIndex = s.nextClosureIndex;
        this.temporaryVariableIndex = s.temporaryVariableIndex;
        this.hasLoops = s.hasLoops;
        this.hasUnusedImplicitBlockArg = s.hasUnusedImplicitBlockArg;
        this.instrList = new ArrayList<Instr>();
        this.closures = new ArrayList<IRClosure>();
        this.dfProbs = new HashMap<String, DataFlowProblem>();
        this.nextVarIndex = new HashMap<String, Integer>(); // SSS FIXME: clone!
        this.cfg = null;
        this.linearizedInstrArray = null;
        this.linearizedBBList = null;
        this.canModifyCode = s.canModifyCode;
        this.canCaptureCallersBinding = s.canCaptureCallersBinding;
        this.bindingHasEscaped = s.bindingHasEscaped;
        this.usesEval = s.usesEval;
        this.usesZSuper = s.usesZSuper;

        this.localVars = new LocalVariableAllocator(); // SSS FIXME: clone!
        this.localVars.nextSlot = s.localVars.nextSlot;
    }
    
    public IRScope(IRManager manager, IRScope lexicalParent, String name, 
            String fileName, int lineNumber, StaticScope staticScope) {
        this.manager = manager;
        this.lexicalParent = lexicalParent;        
        this.name = name;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.staticScope = staticScope;
        this.threadPollInstrsCount = 0;
        this.nextClosureIndex = 0;
        this.temporaryVariableIndex = -1;
        this.instrList = new ArrayList<Instr>();
        this.closures = new ArrayList<IRClosure>();
        this.dfProbs = new HashMap<String, DataFlowProblem>();
        this.nextVarIndex = new HashMap<String, Integer>();
        this.cfg = null;
        this.linearizedInstrArray = null;
        this.linearizedBBList = null;
        this.hasLoops = false;
        this.hasUnusedImplicitBlockArg = false;

        // These flags are true by default!
        this.canModifyCode = true;
        this.canCaptureCallersBinding = true;
        this.bindingHasEscaped = true;
        this.usesEval = true;
        this.usesZSuper = true;

        this.localVars = new LocalVariableAllocator();
        synchronized(globalScopeCount) { this.scopeId = globalScopeCount++; }
    }

    @Override
    public int hashCode() {
        return scopeId;
    }

    public void addClosure(IRClosure c) {
        closures.add(c);
    }
    
    public Instr getLastInstr() {
        return instrList.get(instrList.size() - 1);
    }
    
    public void addInstr(Instr i) {
        if (i instanceof ThreadPollInstr) threadPollInstrsCount++;
        instrList.add(i);
    }

    public LocalVariable getNewFlipStateVariable() {
        return getLocalVariable("%flip_" + allocateNextPrefixedName("%flip"), 0);
    }

    public void initFlipStateVariable(Variable v, Operand initState) {
        // Add it to the beginning
        instrList.add(0, new CopyInstr(v, initState));
    }

    public boolean isForLoopBody() {
        return false;
    }
    
    public Label getNewLabel(String prefix) {
        return new Label(prefix + "_" + allocateNextPrefixedName(prefix));
    }

    public Label getNewLabel() {
        return getNewLabel("LBL");
    }    

    public List<IRClosure> getClosures() {
        return closures;
    }
    
    public IRManager getManager() {
        return manager;
    }
    
    /**
     *  Returns the lexical scope that contains this scope definition
     */
    public IRScope getLexicalParent() {
        return lexicalParent;
    }
    
    public StaticScope getStaticScope() {
        return staticScope;
    }    
    
    public IRMethod getNearestMethod() {
        IRScope current = this;

        while (current != null && !(current instanceof IRMethod)) {
            current = current.getLexicalParent();
        }

        return (IRMethod) current;
    }

    public IRScope getNearestFlipVariableScope() {
        IRScope current = this;

        while (current != null && !current.isFlipScope()) {
            current = current.getLexicalParent();
        }
        
        return current;
    }

    public IRScope getNearestTopLocalVariableScope() {
        IRScope current = this;

        while (current != null && !current.isTopLocalVariableScope()) {
            current = current.getLexicalParent();
        }
        
        return current;
    }
    
    /**
     * Returns the nearest scope which we can extract a live module from.  If
     * this returns null (like for evals), then it means it cannot be statically
     * determined.
     */
    public IRScope getNearestModuleReferencingScope() {
        IRScope current = this;

        while (!(current instanceof IRModuleBody)) {
            // When eval'ing, we dont have a lexical view of what module we are nested in
            // because binding_eval, class_eval, module_eval, instance_eval can switch
            // around the lexical scope for evaluation to be something else.            
            if (current == null || current instanceof IREvalScript) return null;
            
            current = current.getLexicalParent();
        }

        return current;
    }
    
    public String getName() {
        return name;
    }

    public void setName(String name) { // This is for IRClosure ;(
        this.name = name;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * Returns the top level scope
     */
    public IRScope getTopLevelScope() {
        IRScope current = this;
        
        for (; current != null && !current.isScriptScope(); current = current.getLexicalParent()) {}
        
        return current;
    }

    public boolean isNestedInClosure(IRClosure closure) {
        for (IRScope s = this; s != null && !s.isTopLocalVariableScope(); s = s.getLexicalParent()) {
            if (s == closure) return true;
        }
        
        return false;
    }

    public void setHasLoopsFlag(boolean f) {
        hasLoops = true;
    }

    public boolean hasLoops() {
        return hasLoops;
    }

    public void setCodeModificationFlag(boolean f) { 
        canModifyCode = f;
    }

    public boolean modifiesCode() { 
        return canModifyCode;
    }

    public boolean bindingHasEscaped() {
        return bindingHasEscaped;
    }

    public boolean usesEval() {
        return usesEval;
    }

    public boolean usesZSuper() {
        return usesZSuper;
    }

    public boolean canCaptureCallersBinding() {
        return canCaptureCallersBinding;
    }

    public CFG buildCFG() {
        cfg = new CFG(this);
        cfg.build(instrList);
        // Clear out instruction list after CFG has been built.
        this.instrList = null;  
        return cfg;
    }

    protected void setCFG(CFG cfg) {
       this.cfg = cfg;
    }
    
    public CFG getCFG() {
        return cfg;
    }

    public void runCompilerPassOnNestedScopes(CompilerPass p) {
        // Do nothing...subclasses should override
    }

    public void runCompilerPass(CompilerPass p) {
        boolean isPreOrder = p.isPreOrder();

        if (isPreOrder) p.run(this);

        runCompilerPassOnNestedScopes(p);

        if (!isPreOrder) p.run(this);
    }
    
    private Instr[] prepareInstructionsForInterpretation() {
        if (linearizedInstrArray != null) return linearizedInstrArray; // Already prepared

        try {
            buildLinearization(); // FIXME: compiler passes should have done this
            depends(linearization());
        } catch (RuntimeException e) {
            LOG.error("Error linearizing cfg: ", e);
            CFG c = cfg();
            LOG.error("\nGraph:\n" + c.toStringGraph());
            LOG.error("\nInstructions:\n" + c.toStringInstrs());
            throw e;
        }

        // Set up IPCs
        HashMap<Label, Integer> labelIPCMap = new HashMap<Label, Integer>();
        List<Label> labelsToFixup = new ArrayList<Label>();
        List<Instr> newInstrs = new ArrayList<Instr>();
        int ipc = 0;
        for (BasicBlock b : linearizedBBList) {
            labelIPCMap.put(b.getLabel(), ipc);
            labelsToFixup.add(b.getLabel());
            for (Instr i : b.getInstrs()) {
                if (!(i instanceof ReceiveSelfInstr)) {
                    newInstrs.add(i);
                    ipc++;
                }
            }
        }

        // Fix up labels
        for (Label l : labelsToFixup) {
            l.setTargetPC(labelIPCMap.get(l));
        }

        // Exit BB ipc
        cfg().getExitBB().getLabel().setTargetPC(ipc + 1);

        linearizedInstrArray = newInstrs.toArray(new Instr[newInstrs.size()]);
        return linearizedInstrArray;
    }

    private void printPass(String message) {
        if (RubyInstanceConfig.IR_COMPILER_DEBUG) {
            LOG.info("################## " + message + "##################");
            runCompilerPass(new IRPrinter());        
        }
    }

    private void runCompilerPasses() {
        // forcibly clear out the shared eval-scope variable allocator each time this method executes
        initEvalScopeVariableAllocator(true); 

        // SSS FIXME: We should configure different optimization levels
        // and run different kinds of analysis depending on time budget.  Accordingly, we need to set
        // IR levels/states (basic, optimized, etc.) and the
        // ENEBO: If we use a MT optimization mechanism we cannot mutate CFG
        // while another thread is using it.  This may need to happen on a clone()
        // and we may need to update the method to return the new method.  Also,
        // if this scope is held in multiple locations how do we update all references?

        printPass("Before local optimization pass");
        runCompilerPass(new LocalOptimizationPass());
        printPass("After local optimization pass");

        runCompilerPass(new CFGBuilder());
        if (!RubyInstanceConfig.IR_TEST_INLINER.equals("none")) {
            if (RubyInstanceConfig.IR_COMPILER_DEBUG) {
                LOG.info("Asked to inline " + RubyInstanceConfig.IR_TEST_INLINER);
            }
            runCompilerPass(new InlineTest(RubyInstanceConfig.IR_TEST_INLINER));
            runCompilerPass(new LocalOptimizationPass());
            printPass("After inline");
        }        
        // Do not run dead-code-elimination on eval-scripts because they might
        // update their enclosing environments.
        if (!(this instanceof IREvalScript)) {
            if (RubyInstanceConfig.IR_LIVE_VARIABLE) runCompilerPass(new LiveVariableAnalysis());
            if (RubyInstanceConfig.IR_DEAD_CODE) runCompilerPass(new DeadCodeElimination());
            if (RubyInstanceConfig.IR_DEAD_CODE) printPass("After DCE ");
        }
        runCompilerPass(new LinearizeCFG());
        printPass("After CFG Linearize");
    }

    /** Run any necessary passes to get the IR ready for interpretation */
    public synchronized Instr[] prepareForInterpretation() {
        // If the instruction array exists, someone has taken care of setting up the CFG and preparing the instructions
        if (linearizedInstrArray != null) return linearizedInstrArray;

        // Build CFG and run compiler passes, if necessary
        if (getCFG() == null) runCompilerPasses();

        // Linearize CFG, etc.
        return prepareInstructionsForInterpretation();
    }

    /** Run any necessary passes to get the IR ready for compilation */
    public synchronized void prepareForCompilation() {
        // Build CFG and run compiler passes, if necessary
        if (getCFG() == null) runCompilerPasses();
    }

    // SSS FIXME: This method does nothing useful right now.
    // hasEscapedBinding is the crucial flag and it continues to be unconditionally true.
    public void computeScopeFlags() {
        // init
        canModifyCode = true;
        canCaptureCallersBinding = false;
        usesZSuper = false;

        // recompute flags -- we could be calling this method different times
        // definitely once after ir generation and local optimizations propagates constants locally
        // but potentially at a later time after doing ssa generation and constant propagation
        boolean receivesClosureArg = false;
        for (Instr i: getInstrs()) {
            if (i instanceof ReceiveClosureInstr)
                receivesClosureArg = true;

            if (i instanceof ZSuperInstr) {
                canCaptureCallersBinding = true;
                usesZSuper = true;
            }

            if (i instanceof CallBase) {
                CallBase call = (CallBase) i;

                Operand o = ((CallBase)i).getClosureArg(getManager().getNil());
                if (o != null) {
                    if (o instanceof WrappedIRClosure) {
                        IRClosure cl = ((WrappedIRClosure)o).getClosure();
                        cl.computeScopeFlags();
                        if (cl.usesZSuper()) usesZSuper = true;
                    } else {
                        usesZSuper = true;
                    }
                }

                // If this method receives a closure arg, and this call is an eval that has more than 1 argument,
                // it could be using the closure as a binding -- which means it could be using pretty much any
                // variable from the caller's binding!
                if (receivesClosureArg && call.canBeEval() && (call.getCallArgs().length > 1))
                    canCaptureCallersBinding = true;
            }
        }
    }

    public abstract String getScopeName();

    @Override
    public String toString() {
        return getScopeName() + " " + getName() + "[" + getFileName() + ":" + getLineNumber() + "]";
    }    

    public String toStringInstrs() {
        StringBuilder b = new StringBuilder();

        int i = 0;
        for (Instr instr : instrList) {
            if (i > 0) b.append("\n");
            
            b.append("  ").append(i).append('\t').append(instr);
            
            i++;
        }

        if (!closures.isEmpty()) {
            b.append("\n\n------ Closures encountered in this scope ------\n");
            for (IRClosure c: closures)
                b.append(c.toStringBody());
            b.append("------------------------------------------------\n");
        }

        return b.toString();
    }

    public String toStringVariables() {
        Map<Variable, Integer> ends = new HashMap<Variable, Integer>();
        Map<Variable, Integer> starts = new HashMap<Variable, Integer>();
        SortedSet<Variable> variables = new TreeSet<Variable>();
        
        for (int i = instrList.size() - 1; i >= 0; i--) {
            Instr instr = instrList.get(i);

            if (instr instanceof ResultInstr) {
                Variable var = ((ResultInstr) instr).getResult();
                variables.add(var);
                starts.put(var, i);
            }

            for (Operand operand : instr.getOperands()) {
                if (operand != null && operand instanceof Variable && ends.get((Variable)operand) == null) {
                    ends.put((Variable)operand, i);
                    variables.add((Variable)operand);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Variable var : variables) {
            Integer end = ends.get(var);
            if (end != null) { // Variable is actually used somewhere and not dead
                if (i > 0) sb.append("\n");
                i++;
                sb.append("    ").append(var).append(": ").append(starts.get(var)).append("-").append(end);
            }
        }

        return sb.toString();
    }

    /******
     * SSS: Not used -- this was something headius wrote way-back
     *
    @Interp
    public Iterator<LocalVariable> getLiveLocalVariables() {
        Map<LocalVariable, Integer> ends = new HashMap<LocalVariable, Integer>();
        Map<LocalVariable, Integer> starts = new HashMap<LocalVariable, Integer>();
        Set<LocalVariable> variables = new TreeSet<LocalVariable>();

        for (int i = instrList.size() - 1; i >= 0; i--) {
            Instr instr = instrList.get(i);

            // TODO: Instruction encode whether arguments are optional/required/block
            // TODO: PErhaps this should be part of allocate and not have a generic
            //    getLiveLocalVariables...perhaps we just need this to setup static scope

            Variable variable = instr.result;

            if (variable != null && variable instanceof LocalVariable) {
                variables.add((LocalVariable) variable);
                starts.put((LocalVariable) variable, i);
            } 

            for (Operand operand : instr.getOperands()) {
                if (!(operand instanceof LocalVariable)) continue;

                variable = (LocalVariable) operand;

                if (ends.get((LocalVariable) variable) == null) {
                    ends.put((LocalVariable) variable, i);
                    variables.add((LocalVariable) variable);
                }
            }
        }

        return variables.iterator();
    }
    **/

    // SSS FIXME: This is unused code.
    /**
     * Create and (re)assign a static scope.  In general local variables should
     * never change even if we optimize more, but I was not positive so I am
     * pretending this can change over time.  The obvious secondary benefit
     * to storing this on execution scope is we can grab it when we allocate
     * static scopes for all closures.
     *
     * Note: We are missing a distinct life-cycle point to run methods like this
     * since this method can be modified at any point.  Not being fully set up
     * is less of an issue since we are calling this when we construct a live
     * runtime version of the method/closure etc, but for profiled optimizations
     * this is less clear if/when we can run this. <-- Assumes this ever needs
     * changing.
     *
     * @param parent scope should be non-null for all closures and null for methods
    @Interp
    public StaticScope allocateStaticScope(StaticScope parent) {
        Iterator<LocalVariable> variables = getLiveLocalVariables();
        StaticScope scope = constructStaticScope(parent);

        while (variables.hasNext()) {
            LocalVariable variable = variables.next();
            int destination = scope.addVariable(variable.getName());
            System.out.println("Allocating " + variable + " to " + destination);

                    // Ick: Same Variable objects are not used for all references to the same variable.  S
                    // o setting destination on one will not set them on all
            variable.setLocation(destination);
        }

        return scope;
    }
     */

    /** ---------------------------------------
     * SSS FIXME: What is this method for?
    @Interp
    public void calculateParameterCounts() {
        for (int i = instrList.size() - 1; i >= 0; i--) {
            Instr instr = instrList.get(i);
        }
    }
     ------------------------------------------ **/

    public LocalVariable getSelf() {
        return Self.SELF;
    }

    public Variable getCurrentModuleVariable() {
        return getNewTemporaryVariable(Variable.CURRENT_MODULE);
    }

    public Variable getCurrentScopeVariable() {
        return getNewTemporaryVariable(Variable.CURRENT_SCOPE);
    }

    public abstract LocalVariable getImplicitBlockArg();

    public void markUnusedImplicitBlockArg() {
        hasUnusedImplicitBlockArg = true;
    }

    public LocalVariable findExistingLocalVariable(String name, int depth) {
        return localVars.getVariable(name);
    }

    /**
     * Find or create a local variable.  By default, scopes are assumed to
     * only check current depth.  Blocks/Closures override this because they
     * have special nesting rules.
     */
    public LocalVariable getLocalVariable(String name, int scopeDepth) {
        LocalVariable lvar = findExistingLocalVariable(name, scopeDepth);
        if (lvar == null) {
            lvar = new LocalVariable(name, scopeDepth, localVars.nextSlot);
            localVars.putVariable(name, lvar);
        }

        return lvar;
    }

    public LocalVariable getNewLocalVariable(String name, int depth) {
        throw new RuntimeException("getNewLocalVariable should be called for: " + this.getClass().getName());
    }

    protected void initEvalScopeVariableAllocator(boolean reset) {
        if (reset || evalScopeVars == null) evalScopeVars = new LocalVariableAllocator();
    }
    
    public Variable getNewTemporaryVariable() {
        temporaryVariableIndex++;
        return new TemporaryVariable(temporaryVariableIndex);
    }    

    public Variable getNewTemporaryVariable(String name) {
        temporaryVariableIndex++;
        return new TemporaryVariable(name, temporaryVariableIndex);
    }    

    public void resetTemporaryVariables() {
        temporaryVariableIndex = -1;
    }
    
    public int getTemporaryVariableSize() {
        return temporaryVariableIndex + 1;
    }
    
    // Generate a new variable for inlined code (for ease of debugging, use differently named variables for inlined code)
    public Variable getNewInlineVariable(String inlinePrefix, Variable v) {
        if (v instanceof LocalVariable) {
            LocalVariable lv = (LocalVariable)v;
            return getLocalVariable(inlinePrefix + lv.getName(), lv.getScopeDepth());
        } else {
            return getNewTemporaryVariable();
        }
    }

    public int getThreadPollInstrsCount() {
        return threadPollInstrsCount;
    }
    
    public int getLocalVariablesCount() {
        return localVars.nextSlot;
    }

    public int getUsedVariablesCount() {
        // System.out.println("For " + this + ", # lvs: " + nextLocalVariableSlot);
        // # local vars, # flip vars
        return localVars.nextSlot + getPrefixCountSize("%flip");
    }
    
    public void setUpUseDefLocalVarMaps() {
        definedLocalVars = new java.util.HashSet<Variable>();
        usedLocalVars = new java.util.HashSet<Variable>();
        for (BasicBlock bb : cfg().getBasicBlocks()) {
            for (Instr i : bb.getInstrs()) {
                for (Variable v : i.getUsedVariables()) {
                    if (v instanceof LocalVariable) usedLocalVars.add(v);
                }
                
                if (i instanceof ResultInstr) {
                    Variable v = ((ResultInstr) i).getResult();
                    
                    if (v instanceof LocalVariable) definedLocalVars.add(v);
                }
            }
        }

        for (IRClosure cl : getClosures()) {
            cl.setUpUseDefLocalVarMaps();
        }
    }

    public boolean usesLocalVariable(Variable v) {
        if (usedLocalVars == null) setUpUseDefLocalVarMaps();
        if (usedLocalVars.contains(v)) return true;

        for (IRClosure cl : getClosures()) {
            if (cl.usesLocalVariable(v)) return true;
        }

        return false;
    }

    public boolean definesLocalVariable(Variable v) {
        if (definedLocalVars == null) setUpUseDefLocalVarMaps();
        if (definedLocalVars.contains(v)) return true;

        for (IRClosure cl : getClosures()) {
            if (cl.definesLocalVariable(v)) return true;
        }

        return false;
    }    
    
    public void setDataFlowSolution(String name, DataFlowProblem p) {
        dfProbs.put(name, p);
    }

    public DataFlowProblem getDataFlowSolution(String name) {
        return dfProbs.get(name);
    }
    
    // This should only be used to do pre-cfg opts and to build the CFG.
    // Everyone else should use the CFG.
    public List<Instr> getInstrs() {
        if (cfg != null) throw new RuntimeException("Please use the CFG to access this scope's instructions.");
        return instrList;
    }

    public Instr[] getInstrsForInterpretation() {
        return linearizedInstrArray;
    }
    
    public List<BasicBlock> buildLinearization() {
        if (linearizedBBList != null) return linearizedBBList; // Already linearized
        
        linearizedBBList = CFGLinearizer.linearize(cfg());
        
        return linearizedBBList;
    }
    
    // SSS FIXME: Extremely inefficient
    public int getRescuerPC(Instr excInstr) {
        depends(cfg());
        
        for (BasicBlock b : linearizedBBList) {
            for (Instr i : b.getInstrs()) {
                if (i == excInstr) {
                    BasicBlock rescuerBB = cfg().getRescuerBBFor(b);
                    return (rescuerBB == null) ? -1 : rescuerBB.getLabel().getTargetPC();
                }
            }
        }

        // SSS FIXME: Cannot happen! Throw runtime exception
        LOG.error("Fell through looking for rescuer ipc for " + excInstr);
        return -1;
    }

    // SSS FIXME: Extremely inefficient
    public int getEnsurerPC(Instr excInstr) {
        depends(cfg());
        
        for (BasicBlock b : linearizedBBList) {
            for (Instr i : b.getInstrs()) {
                if (i == excInstr) {
                    BasicBlock ensurerBB = cfg.getEnsurerBBFor(b);
                    return (ensurerBB == null) ? -1 : ensurerBB.getLabel().getTargetPC();
                }
            }
        }

        // SSS FIXME: Cannot happen! Throw runtime exception
        LOG.error("Fell through looking for ensurer ipc for " + excInstr);
        return -1;
    }
    
    public List<BasicBlock> linearization() {
        depends(cfg());
        
        assert linearizedBBList != null: "You have not run linearization";
        
        return linearizedBBList;
    }
    
    protected void depends(Object obj) {
        assert obj != null: "Unsatisfied dependency and this depends() was set " +
                "up wrong.  Use depends(build()) not depends(build).";
    }
    
    public CFG cfg() {
        assert cfg != null: "Trying to access build before build started";
        return cfg;
    }     

    public void splitCalls() {
        // FIXME: (Enebo) We are going to make a SplitCallInstr so this logic can be separate
        // from unsplit calls.  Comment out until new SplitCall is created.
//        for (BasicBlock b: getNodes()) {
//            List<Instr> bInstrs = b.getInstrs();
//            for (ListIterator<Instr> it = ((ArrayList<Instr>)b.getInstrs()).listIterator(); it.hasNext(); ) {
//                Instr i = it.next();
//                // Only user calls, not Ruby & JRuby internal calls
//                if (i.operation == Operation.CALL) {
//                    CallInstr call = (CallInstr)i;
//                    Operand   r    = call.getReceiver();
//                    Operand   m    = call.getMethodAddr();
//                    Variable  mh   = _scope.getNewTemporaryVariable();
//                    MethodLookupInstr mli = new MethodLookupInstr(mh, m, r);
//                    // insert method lookup at the right place
//                    it.previous();
//                    it.add(mli);
//                    it.next();
//                    // update call address
//                    call.setMethodAddr(mh);
//                }
//            }
//        }
//
//        List<IRClosure> closures = _scope.getClosures();
//        if (!closures.isEmpty()) {
//            for (IRClosure c : closures) {
//                c.getCFG().splitCalls();
//            }
//        }
    }    

    public void inlineMethod(IRScope method, RubyModule implClass, int classToken, BasicBlock basicBlock, CallBase call) {
        depends(cfg());
        new CFGInliner(cfg).inlineMethod(method, implClass, classToken, basicBlock, call);

        // Reset state
        linearizedBBList = null;
        linearizedInstrArray = null;
        dfProbs = new HashMap<String, DataFlowProblem>();
        runCompilerPass(new DeadCodeElimination());
    }
    
    
    public void buildCFG(List<Instr> instrList) {
        CFG newBuild = new CFG(this);
        newBuild.build(instrList);
        cfg = newBuild;
    }    

    public void buildDominatorTree(DominatorTreeBuilder builder) {
        depends(cfg());

        // FIXME: Add result from this build and add to CFG as a field, then add depends() for htings which use it.
        builder.buildDominatorTree(cfg, cfg.postOrderList(), cfg.getMaxNodeID());
    }
    
    /* Record a begin block -- not all scope implementations can handle them */
    public void recordBeginBlock(IRClosure beginBlockClosure) {
        throw new RuntimeException("BEGIN blocks cannot be added to: " + this.getClass().getName());
    }

    /* Record an end block -- not all scope implementations can handle them */
    public void recordEndBlock(IRClosure endBlockClosure) {
        throw new RuntimeException("END blocks cannot be added to: " + this.getClass().getName());
    } 
    
    // Enebo: We should just make n primitive int and not take the hash hit
    protected int allocateNextPrefixedName(String prefix) {
        int index = getPrefixCountSize(prefix);
        
        nextVarIndex.put(prefix, index + 1);
        
        return index;
    }

    protected void resetVariableCounter(String prefix) {
        nextVarIndex.remove(prefix);
    }

    protected int getPrefixCountSize(String prefix) {
        Integer index = nextVarIndex.get(prefix);

        if (index == null) return 0;

        return index.intValue();
    }
    
    public RubyModule getContainerModule() {
//        System.out.println("GET: container module of " + getName() + " with hc " + hashCode() + " to " + containerModule.getName());
        return containerModule;
    }

    public int getNextClosureId() {
        nextClosureIndex++;

        return nextClosureIndex;
    }
    
    /**
     * Does this scope represent a module body?  (SSS FIXME: what about script or eval script bodies?)
     */
    public boolean isModuleBody() {
        return false;
    }

    public boolean isFlipScope() {
        return true;
    }
    
    public boolean isTopLocalVariableScope() {
        return true;
    }
    
    /**
     * Is this an eval script or a regular file script?
     */
    public boolean isScriptScope() {
        return false;
    }
}

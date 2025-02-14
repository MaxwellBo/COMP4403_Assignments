package tree;
import java.util.*;

import machine.Operation;
import machine.StackMachine;
import source.Errors;
import syms.SymEntry;
import syms.Type;
import tree.StatementNode.*;

/** class CodeGenerator implements code generation using the
 * visitor pattern to traverse the abstract syntax tree.
 * @version $Revision: 22 $  $Date: 2014-05-20 15:14:36 +1000 (Tue, 20 May 2014) $ 
 */
public class CodeGenerator implements DeclVisitor, StatementTransform<Code>,
                    ExpTransform<Code> {
    /** Current static level of nesting into procedures. */
    private int staticLevel;
    
    /** Table of code for each procedure */
    private Procedures procedures;
    
    /** Error message handler */
    private Errors errors;
    /** Track the tree node currently being checked (for debugging) */
    private Stack<String> nodeStack;

    public CodeGenerator(Errors errors) {
        super();
        this.errors = errors;
        nodeStack = new Stack<String>();
        procedures = new Procedures();
    }

    /*-------------------- Main Method to start code generation --------*/

    /** Main generate code for this tree. */
    public Procedures generateCode( DeclNode.ProgramNode node ) {
        beginGen( "Program" );
        staticLevel = 1;        // Main program is at static level 1
        /* Generate the code for the main program and all procedures */
        visitProcedureNode( node );
        endGen( "Program" );
        return procedures;
    }
    
    /* -------------------- Visitor methods ----------------------------*/

    /** Generate code for a single procedure. */
    public void visitProcedureNode( DeclNode.ProcedureNode node ) {
        beginGen( "Procedure" );
        // Generate code for the block
        Code code = visitBlockNode( node.getBlock() );
        procedures.addProcedure( node.getProcEntry(), code );
        endGen( "Procedure" );
    }

    /** Generate code for a block. */
    public Code visitBlockNode( BlockNode node ) {
        beginGen( "Block" );
        /** Generate code to allocate space for local variables on
         * procedure entry.
         */
        Code code = new Code();
        code.genAllocStack( node.getBlockLocals().getVariableSpace() );
        /* Generate the code for the body */
        code.append( node.getBody().genCode( this ) );
        code.generateOp( Operation.RETURN );
        /** Generate code for local procedures. */
        /* Static level is one greater for the procedures. */
        staticLevel++;
        node.getProcedures().accept(this);
        staticLevel--;
        endGen( "Block" );
        return code;
    }

    /** Code generation for a declaration list */
    public void visitDeclListNode( DeclNode.DeclListNode node ) {
        beginGen( "DeclList" );
        for( DeclNode decl : node.getDeclarations() ) {
            decl.accept( this );
        }
        endGen( "DeclList" );
    }

    /*************************************************
     *  Statement node code generation visit methods
     *************************************************/
    /** Code generation for an erroneous statement should not be attempted. */
    public Code visitStatementErrorNode( StatementNode.ErrorNode node ) {
        errors.fatal( "PL0 Internal error: generateCode for Statement Error Node",
                node.getLocation() );
        return null;
    }

    /** Code generation for an assignment statement. */
    public Code visitAssignmentNode( StatementNode.AssignmentNode node ) {
        beginGen( "Assignment" );
        Code code = new Code();

        for( SingleAssignNode s : node.getAssignments() ) {
            /* Generate code to evaluate the expression */
            code.append(s.getExp().genCode( this ));
        }

        List<SingleAssignNode> reversed = new ArrayList<>(node.getAssignments());
        Collections.reverse(reversed);

        for( SingleAssignNode s : reversed ) {
            /* Generate the code to load the address of the variable */
            code.append( s.getVariable().genCode( this ) );
            /* Generate the store based on the type/size of value */
            code.genStore( (Type.ReferenceType)s.getVariable().getType() );
        }

        endGen( "Assignment" );
        return code;
    }

    /** Code generation for an single assign */
    public Code visitSingleAssignNode(SingleAssignNode node) {
        beginGen( "SingleAssign" );
        /* Generate code to evaluate the expression */
        Code code = node.getExp().genCode( this );
        /* Generate the code to load the address of the variable */
        code.append( node.getVariable().genCode( this ) );
        /* Generate the store based on the type/size of value */
        code.genStore( (Type.ReferenceType)node.getVariable().getType() );
        endGen( "SingleAssign" );
        return code;
    }
    /** Generate code for a "write" statement. */
    public Code visitWriteNode( StatementNode.WriteNode node ) {
        beginGen( "Write" );
        Code code = node.getExp().genCode( this );
        code.generateOp( Operation.WRITE );
        endGen( "Write" );
        return code;
    }
    /** Generate code for a "call" statement. */
    public Code visitCallNode( StatementNode.CallNode node ) {
        beginGen( "Call" );
        SymEntry.ProcedureEntry proc = node.getEntry();
        Code code = new Code();
        /* Generate the call instruction. The second parameter is the
         * procedure's symbol table entry. The actual address is resolved 
         * at load time.
         */
        code.genCall( staticLevel - proc.getLevel(), proc );
        endGen( "Call" );
        return code;
    }
    /** Generate code for a statement list */
    public Code visitStatementListNode( StatementNode.ListNode node ) {
        beginGen( "StatementList" );
        Code code = new Code();
        for( StatementNode s : node.getStatements() ) {
            code.append( s.genCode( this ) );
        }
        endGen( "StatementList" );
        return code;
    }

    /** Generate code for an "if" statement. */
    public Code visitIfNode(StatementNode.IfNode node) {
        beginGen( "If" );
        /* Generate code to evaluate the condition and then and else parts */
        Code code = node.getCondition().genCode( this );
        Code thenCode = node.getThenStmt().genCode( this );
        Code elseCode = node.getElseStmt().genCode( this );
        /* Append a branch over then part code */
        code.genJumpIfFalse( thenCode.size() + Code.SIZE_JUMP_ALWAYS );
        /* Next append the code for the then part */
        code.append( thenCode );
        /* Append branch over the else part */
        code.genJumpAlways( elseCode.size() );
        /* Finally append the code for the else part */
        code.append( elseCode );
        endGen( "If" );
        return code;
    }
 
    /** Generate code for a "while" statement. */
    public Code visitWhileNode(StatementNode.WhileNode node) {
        beginGen( "While" );
        /* Generate the code to evaluate the condition. */
        Code code = node.getCondition().genCode( this );
        /* Generate the code for the loop body */
        Code bodyCode = node.getLoopStmt().genCode( this );
        /* Add a branch over the loop body on false.
         * The offset is the size of the loop body code plus 
         * the size of the branch to follow the body.
         */
        code.genJumpIfFalse( bodyCode.size() + Code.SIZE_JUMP_ALWAYS );
        /* Append the code for the body */
        code.append( bodyCode );
        /* Add a branch back to the condition.
         * The offset is the total size of the current code plus the
         * size of a Jump Always (being generated).
         */
        code.genJumpAlways( -(code.size() + Code.SIZE_JUMP_ALWAYS) );
        endGen( "While" );
        return code;
    }
    /** Generate code for a "skip" statement. */
    public Code visitSkipNode(StatementNode.SkipNode node) {
        beginGen( "Skip" );
        Code code = new Code();
        endGen("Skip");
        return code;
    }
    /** Generate code for a "case" statement. */
    public Code visitCaseNode(StatementNode.CaseNode node) {
        beginGen( "Case" );

        Code collector = new Code();
        Code entryCollector = new Code();
        Code branchCollector = new Code();
        Code tableCollector = new Code();

        // Arrange our cases in order
        List<CaseBranchNode> branches = new ArrayList<>(node.getBranches());
        Collections.sort(branches, new Comparator<CaseBranchNode>() {
            @Override
            public int compare(CaseBranchNode o1, CaseBranchNode o2) {
                return Integer.compare(o1.getLabel().getValue(), o2.getLabel().getValue());
            }
        });

        // We'll use this to see if our labels are able to match the specified condition
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        if (branches.size() != 0) {
            min = branches.get(0).getLabel().getValue();
            max = branches.get(branches.size() - 1).getLabel().getValue();
        }

        // We'll use this to set the size of the table
        int range = max - min;

        List<Integer> branchLabels = new ArrayList<>();
        List<Code> branchCodes = new ArrayList<>();

        for (CaseBranchNode b : branches) {
            branchLabels.add(b.getLabel().getValue());
            branchCodes.add(b.genCode( this ));
        }

        // This is going to be our default branch
        branchLabels.add(Integer.MAX_VALUE);
        // In the event of all cases falling through, we want to go to the
        // same code - either defaulting or exploding
        if (node.getDefault() != null) {
            branchCodes.add(node.getDefault().genCode(this));
        } else {
            Code runtimeError = new Code();
            runtimeError.genLoadConstant(StackMachine.CASE_LABEL_MISSING);
            runtimeError.generateOp(Operation.STOP);
            branchCodes.add(runtimeError);
        }

        Map<Integer, Integer> labelValueToOffset = new HashMap<>();

        while (branchLabels.size() != 0) {
            assert branchLabels.size() == branchCodes.size();
            int labelValue = branchLabels.remove(0);
            Code code = branchCodes.remove(0);

            // Normalize branch labels (-min) so that they can be jumped into
            // from index 0
            labelValueToOffset.put(labelValue - min, branchCollector.size());

            // Put down the branch code
            branchCollector.append(code);

            // Gen the offset for the goto OUT
            // AKA: all the remaining code and all their goto OUT's
            int overRemainingBranchesOffset = branchCodes.stream()
                    .mapToInt(c -> c.size()) // size of the branch code
                    .sum() + (branchCodes.size() * Code.SIZE_JUMP_ALWAYS);
            //                            ^ and the amount of GOTOS

            // Put down the jump out
            branchCollector.genJumpAlways(overRemainingBranchesOffset);
        }

        // If there were branches in the table
        if (range >= 0) {
            //                v not an error
            for (int i = 0; i <= range; i++) {
                // Find out how far we have to jump to get from the table
                // into the branches
                int overRemainingTableOffset = (range - i) * Code.SIZE_JUMP_ALWAYS;

                // Find out whether we're jumping from a label to a branch
                // or an invalid label to a runtime error or default branch
                int overBranchesOffset = labelValueToOffset.containsKey(i) ?
                        labelValueToOffset.get(i) :
                        labelValueToOffset.get(Integer.MAX_VALUE - min); // get the default branch or explode

                // Put down the jump in
                tableCollector.genJumpAlways(overRemainingTableOffset + overBranchesOffset);
            }
        }

        // We need to generate the jump into table first
        // because the abort into default / explode branch has to
        // jump over this code
        Code branch = new Code();
        branch.genLoadConstant(-min); // Normalize all jumps onto the jump table
        branch.generateOp(Operation.ADD);
        branch.genLoadConstant(Code.SIZE_JUMP_ALWAYS);
        branch.generateOp(Operation.MPY);
        branch.generateOp(Operation.BR); // Jump onto the table

        // Check if the exp is within the min and max ranges of the labels
        // if not, branch to default or explode; if so, branch into the table
        Code rangeCheck = new Code();
        Code condition = node.getTarget().genCode( this );
        rangeCheck.append(condition); // make three copies of the condition
        rangeCheck.generateOp(Operation.DUP);
        rangeCheck.generateOp(Operation.DUP);
        rangeCheck.genLoadConstant(max);
        rangeCheck.generateOp(Operation.LESSEQ); // is leq than max
        rangeCheck.generateOp(Operation.SWAP);
        rangeCheck.genLoadConstant(min);
        rangeCheck.generateOp(Operation.SWAP);
        rangeCheck.generateOp(Operation.LESSEQ); // is greq min
        rangeCheck.generateOp(Operation.AND); // is bounded
        rangeCheck.genJumpIfFalse(branch.size()
                + tableCollector.size()
                + labelValueToOffset.get(Integer.MAX_VALUE - min));
        // Jump over the table and straight into the default / explode branch

        entryCollector.append(rangeCheck);
        entryCollector.append(branch);

        endGen("Case");

        collector.append(entryCollector);
        collector.append(tableCollector);
        collector.append(branchCollector);
        return collector;
    }
    /** Generate code for a "case" statement. */
    public Code visitCaseBranchNode(StatementNode.CaseBranchNode node) {
        beginGen( "CaseBranch" );
        Code code = node.getStatements().genCode( this );
        endGen("CaseBranch");
        return code;
    }
    /*************************************************
     *  Expression node code generation visit methods
     *************************************************/
    /** Code generation for an erroneous expression should not be attempted. */
    public Code visitErrorExpNode( ExpNode.ErrorNode node ) { 
        errors.fatal( "PL0 Internal error: generateCode for ErrorExpNode",
                node.getLocation() );
        return null;
    }

    /** Generate code for a constant expression. */
    public Code visitConstNode( ExpNode.ConstNode node ) {
        beginGen( "Const" );
        Code code = new Code();
        if( node.getValue() == 0 ) {
            code.generateOp( Operation.ZERO );
        } else if( node.getValue() == 1 ) {
            code.generateOp( Operation.ONE );
        } else {
            code.genLoadConstant( node.getValue() );
        }
        endGen( "Const" );
        return code;
    }

    /** Generate code for a "read" expression. */
    public Code visitReadNode( ExpNode.ReadNode node ) {
        beginGen( "Read" );
        Code code = new Code();
        code.generateOp( Operation.READ );
        endGen( "Read" );
        return code;
    }
    
    /** Generate code for a operator expression. */
    public Code visitOperatorNode( ExpNode.OperatorNode node ) {
        beginGen( "Operator" );
        Code code;
        ExpNode args = node.getArg();
        switch ( node.getOp() ) {
        case ADD_OP:
            code = args.genCode( this );
            code.generateOp(Operation.ADD);
            break;
        case SUB_OP:
            code = args.genCode( this );
            code.generateOp(Operation.NEGATE);
            code.generateOp(Operation.ADD);
            break;
        case MUL_OP:
            code = args.genCode( this );
            code.generateOp(Operation.MPY);
            break;
        case DIV_OP:
            code = args.genCode( this );
            code.generateOp(Operation.DIV);
            break;
        case EQUALS_OP:
            code = args.genCode( this );
            code.generateOp(Operation.EQUAL);
            break;
        case LESS_OP:
            code = args.genCode( this );
            code.generateOp(Operation.LESS);
            break;
        case NEQUALS_OP:
            code = args.genCode( this );
            code.generateOp(Operation.EQUAL);
            code.genBoolNot();
            break;
        case LEQUALS_OP:
            code = args.genCode( this );
            code.generateOp(Operation.LESSEQ);
            break;
        case GREATER_OP:
            /* Generate argument values in reverse order and use LESS */
            code = genArgsInReverse( (ExpNode.ArgumentsNode)args );
            code.generateOp(Operation.LESS);
            break;
        case GEQUALS_OP:
            /* Generate argument values in reverse order and use LESSEQ */
            code = genArgsInReverse( (ExpNode.ArgumentsNode)args );
            code.generateOp(Operation.LESSEQ);
            break;
        case NEG_OP:
            code = args.genCode( this );
            code.generateOp(Operation.NEGATE);
            break;
        default:
            errors.fatal("PL0 Internal error: Unknown operator",
                    node.getLocation() );
            code = null;
        }
        endGen( "Operator" );
        return code;
    }

    /** Generate the code to load arguments (in order) */
    public Code visitArgumentsNode( ExpNode.ArgumentsNode node ) {
        beginGen( "Arguments" );
        Code code = new Code();
        for( ExpNode exp : node.getArgs() ) {
            code.append( exp.genCode( this ) );
        }
        endGen( "Arguments" );
        return code;
    }
    /** Generate operator operands in reverse order */
    private Code genArgsInReverse( ExpNode.ArgumentsNode args ) {
        beginGen( "ArgsInReverse" );
        List<ExpNode> argList = args.getArgs();
        Code code = new Code();
        for( int i = argList.size()-1; 0 <= i; i-- ) {
            code.append( argList.get(i).genCode( this ) );
        }
        endGen( "ArgsInReverse" );
        return code;
    }
    /** Generate code to dereference an RValue. */
    public Code visitDereferenceNode( ExpNode.DereferenceNode node ) {
        beginGen( "Dereference" );
        Code code = node.getLeftValue().genCode( this );
        code.genLoad( node.getType() );
        endGen( "Dereference" );
        return code;
    }
    /** Generate code for an identifier. */
    public Code visitIdentifierNode(ExpNode.IdentifierNode node) {
        /** Visit the corresponding constant or variable node. */
        errors.fatal("Internal error: code generator called on IdentifierNode",
                node.getLocation() );
        return null;
    }
    /** Generate code for a variable (Exp) reference. */
    public Code visitVariableNode( ExpNode.VariableNode node ) {
        beginGen( "Variable" );
        SymEntry.VarEntry var = node.getVariable();
        Code code = new Code();
        code.genMemRef( staticLevel - var.getLevel(), var.getOffset() );
        endGen( "Variable" );
        return code;
    }
    /** Generate code to perform a bounds check on a subrange. */
    public Code visitNarrowSubrangeNode(ExpNode.NarrowSubrangeNode node) {
        beginGen( "NarrowSubrange" );
        Code code = node.getExp().genCode( this );
        code.genBoundsCheck(node.getSubrangeType().getLower(), 
                node.getSubrangeType().getUpper());
        endGen( "NarrowSubrange" );
        return code;
    }

    /** Generate code to widen a subrange to an integer. */
    public Code visitWidenSubrangeNode(ExpNode.WidenSubrangeNode node) {
        beginGen( "WidenSubrange" );
        // Widening doesn't require anything extra
        Code code = node.getExp().genCode( this );
        endGen( "WidenSubrange" );
        return code;
    }

    /**************************** Support Methods ***************************/
    /** Push current node onto debug rule stack and increase debug level */
    private void beginGen( String node ) {
        nodeStack.push( node );
        errors.debugMessage("Generating " + node );
        errors.incDebug();
    }
    /** Pop current node from debug rule stack and decrease debug level */
    private void endGen( String node ) {
        errors.decDebug();
        errors.debugMessage("End generation of " + node );
        String popped = nodeStack.pop();
        if( node != popped) {
            errors.debugMessage("*** End node " + node + 
                    " does not match start node " + popped);
        }
    }
    /** Debugging message output */
    private void debugMessage( String msg ) {
        errors.debugMessage( msg );
    }


}

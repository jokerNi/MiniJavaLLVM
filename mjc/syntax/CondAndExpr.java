// Copyright (c) Mark P Jones, Portland State University
// Subject to conditions of distribution and use; see LICENSE for details
// February 3 2008 11:12 AM

package syntax;

import compiler.*;
import codegen.*;
import interp.*;

/** Provides a representation for conditional and expressions (&amp;&amp;).
 */
public final class CondAndExpr extends LogicOpExpr {
    public CondAndExpr(Position pos, Expression left, Expression right) {
        super(pos, left, right);
    }

    /** Generate code to evaluate this expression and
     *  leave the result in the specified free variable.
     */
    public void compileExpr(Assembly a, int free) {
        String l1 = a.newLabel();
        String l2 = a.newLabel();
        left.branchFalse(a, l1, free);
        right.branchFalse(a, l1, free);
        a.emit("movl", a.immed(1), a.reg(free));
        a.emit("jmp", l2);

        a.emitLabel(l1);
        a.emit("movl", a.immed(0), a.reg(free));

        a.emitLabel(l2);
    }

    /** Generate code to evaluate this expression and
     *  branch to a specified label if the result is false.
     */
    void branchFalse(Assembly a, String lab, int free) {
        left.branchFalse(a, lab, free);
        right.branchFalse(a, lab, free);
    }

    /** Generate code to evaluate this expression and
     *  branch to a specified label if the result is true.
     */
    void branchTrue(Assembly a, String lab, int free) {
        String l1 = a.newLabel();
        left.branchFalse(a, l1, free);
        right.branchTrue(a, lab, free);
        a.emitLabel(l1);
    }

    /** Evaluate this expression.
     */
    public Value eval(State st) {
        return BoolValue.make(left.eval(st).getBool()
                              && right.eval(st).getBool());
    }
    public Object accept(ExprVisitor visitor, Object data) {
        return visitor.visit(this, data);
    }
}

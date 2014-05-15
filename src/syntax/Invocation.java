// Copyright (c) Mark P Jones, Portland State University
// Subject to conditions of distribution and use; see LICENSE for details
// February 3 2008 11:12 AM

package syntax;

import compiler.*;
import checker.*;
import codegen.*;

import java.util.ArrayList;
import org.llvm.TypeRef;

/** Provides a representation for method invocations.
 */
public abstract class Invocation extends StatementExpr {
    protected Args args;

    public Invocation(Position pos, Args args) {
        super(pos);
        this.args = args;
    }

    /** Type check this expression in places where it is used as a statement.
     *  We override this method in Invocation to deal with methods that
     *  return void.
     */
    void checkExpr(Context ctxt, VarEnv env)
    throws Diagnostic {
        typeInvocation(ctxt, env);
    }

    /** Calculate the type of this method invocation.
     */
    abstract Type typeInvocation(Context ctxt, VarEnv env)
    throws Diagnostic;

    /** Check arguments of a method invocation.
     */
    Type checkInvocation(Context ctxt, VarEnv env, MethEnv menv) {
        menv.accessCheck(ctxt, pos);
        menv.checkArgs(pos, ctxt, env, args);
        return menv.getType();
    }

    /** Check this expression and return an object that describes its
     *  type (or throw an exception if an unrecoverable error occurs).
     */
    public Type typeOf(Context ctxt, VarEnv env) throws Diagnostic {
        Type result = typeInvocation(ctxt, env);
        if (result == null) {
            throw new Failure(pos, "Method does not return a value");
        }
        return result;
    }

    /** Generate code for this method invocation, leaving
     *  the result in the specified free variable.
     */
    abstract void compileInvocation(Assembly a, int free);

    /** Generate code to evaluate this expression and
     *  leave the result in the specified free variable.
     */
    public void compileExpr(Assembly a, int free) {
        a.spillAll(free);
        compileInvocation(a, free);
        a.unspillAll(free);
    }

    public org.llvm.Value llvmInvoke(LLVM l, MethEnv menv, org.llvm.Value function,
                                     org.llvm.Value this_ptr) {
        /* static methods will provide null for this_ptr */
        ArrayList<org.llvm.Value> func_args = new ArrayList<org.llvm.Value>();

        int i = 0;
        if (this_ptr != null) {
            func_args.add(this_ptr);
            i++;
        }

        if (args != null) {
            for (Args a : args) {
                org.llvm.Value arg_val = a.getArg().llvmGen(l);
                func_args.add(arg_val);
                i++;
            }
        }

        String func_name = menv.getName();
        String name = "call_" + func_name;
        if (menv.getType() == Type.VOID) {
            name = "";
        }
        return l.getBuilder().buildCall(function, name, func_args);
    }
}

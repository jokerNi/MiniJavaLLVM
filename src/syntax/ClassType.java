// Copyright (c) Mark P Jones, Portland State University
// Subject to conditions of distribution and use; see LICENSE for details
// February 3 2008 11:12 AM

package syntax;

import checker.*;
import compiler.*;
import codegen.*;
import interp.*;

import java.util.Arrays;
import org.llvm.TypeRef;
import java.util.ArrayList;
import org.llvm.Builder;

import org.llvm.binding.LLVMLibrary.LLVMLinkage;

/** Provides a representation for class types.
 */
public final class ClassType extends Type {
    private Modifiers mods;
    private Id        id;
    private Type      extendsType;
    private Decls     decls;
    private FieldEnv  fields;
    private MethEnv   methods;
    private int       width;    // # bytes for objects of this class
    private int       vfuns;    // # entries in vtable
    private MethEnv[] vtable;   // virtual function table for this class

    private TypeRef llvmType;
    private TypeRef llvmVtable;
    private org.llvm.Value llvmVtableLoc;

    private int fieldCount;
    public ClassType(Modifiers mods, Id id, Type extendsType, Decls decls) {
        this.mods        = mods;
        this.id          = id;
        this.extendsType = extendsType;
        this.decls       = decls;
        this.llvmType = null;
        this.llvmVtable = null;
        this.fieldCount = 1;  /* always reserve one spot for vtable */
    }

    public org.llvm.Value getVtableLoc() {
        return llvmVtableLoc;
    }

    public FieldEnv getFields() {
        return fields;
    }
    public MethEnv getMethods() {
        return methods;
    }

    public int getWidth() {
        return width;
    }

    public MethEnv[] getVtable() {
        return vtable;
    }

    /** Return a printable representation of this type.
     */
    public String toString() {
        return id.toString();
    }

    /** Return the identifier that names this class.
     *
     */
    public Id getId() {
        return id;
    }

    /** Return the position at which the definition for this class appears.
     */
    public Position getPos() {
        return id.getPos();
    }

    /** Return the name of the vtable for this class.
     */
    public String getVTName() {
        return id.getName() + "_0";
    }

    /** Test for equality with another type.
     */
    public boolean equal(Type type) {
        if (type instanceof ClassType) {
            ClassType that = (ClassType)type;
            return that.id.sameId(this.id);
        }
        return false;
    }

    /** Test to see if this type is a class; for ClassTypes, we
     *  just return "this".
     */
    public ClassType isClass() {
        return this;
    }
    private static final int CHECKING = 0;
    private static final int UNCHECKED = (-1);
    private static final int LOWEST = 1;
    private int level = UNCHECKED;

    /** Check this class definition to make sure that there are no
     *  cycles in the class hierarchy, and to process (but not yet
     *  check) any definitions that it contains.
     */
    public void checkClass(Context ctxt) {
        if (level == CHECKING) {
            ctxt.report(new Failure(id.getPos(),
                                    "Cyclic class hierarchy for class " + id));
        } else if (level == UNCHECKED) {
            ClassType extendsClass = null;
            if (extendsType != null) {
                extendsType = extendsType.check(ctxt);
            }
            if (extendsType != null) {
                if (this.equal(extendsType)) {
                    ctxt.report(new Failure(id.getPos(),
                                            "Class " + id + " extends itself!"));
                }
                extendsClass = extendsType.isClass();
                if (extendsClass == null) {
                    ctxt.report(new Failure(id.getPos(),
                                            "Illegal superclass"));
                    extendsType = null;
                } else {
                    level = CHECKING;
                    extendsClass.checkClass(ctxt);
                    level = 1 + extendsClass.level;
                    width = extendsClass.width;
                    fieldCount = extendsClass.fieldCount;
                    vfuns = extendsClass.vfuns;
                }
            } else {
                level = LOWEST;
                width = Assembly.WORDSIZE; // storage for vptr
                vfuns = 0;
            }

            // Now go on to check the members of the class, secure in the
            // knowledge that the superclass has already been done ...
            for (; decls != null; decls = decls.getNext()) {
                decls.addToClass(ctxt, this);
            }

            // Finally, build vtable
            vtable = new MethEnv[vfuns];
            if (vfuns > 0) {
                if (extendsClass != null) {
                    for (int i = 0; i < extendsClass.vfuns; i++) {
                        vtable[i] = extendsClass.vtable[i];
                    }
                }
                MethEnv.addToVTable(methods, vtable);
            }
        }
    }

    /** Return the superclass, if any, of this class.
     */
    public ClassType getSuper() {
        return extendsType.isClass();
    }

    /** Test to see if this class is a supertype of another type.
     */
    public boolean isSuperOf(Type t) {
        if (t.equal(Type.NULL)) {
            return true;
        }
        ClassType that = t.isClass();
        while (that != null && that.level > level) {
            that = that.extendsType.isClass();
        }
        return (that != null && that.id.sameId(this.id));
    }

    /** Add a new field to this class.
     */
    public void addField(Context ctxt, Modifiers mods, Id id, Type type) {
        if (FieldEnv.find(id.getName(), fields) != null) {
            ctxt.report(new Failure(id.getPos(),
                                    "Multiple definitions for field " + id));
        } else if (mods.isStatic()) {
            fields = new FieldEnv(mods, id, type, this, -1,  0, fields);
        } else {
            fields = new FieldEnv(mods, id, type, this, fieldCount++, width, fields);
            width += type.size();
        }
    }

    /** Add a new method to this class.
     */
    public void addMethod(Context ctxt, Modifiers mods, Id id, Type type,
                          VarEnv params, Statement body) {
        if (MethEnv.find(id.getName(), methods) != null) {
            ctxt.report(new Failure(id.getPos(),
                                    "Multiple definitions for method " + id));
        } else {
            int size = VarEnv.fitToFrame(params);
            int slot = (-1);
            if (!mods.isStatic()) {
                size += Assembly.WORDSIZE;      // add `this' pointer
                if (extendsType != null) {
                    // TODO: Need to check for modifiers in override
                    MethEnv env = getSuper().findMethod(id.getName());
                    if (env != null && env.eqSig(type, params)) {
                        slot = env.getSlot();
                    } else {
                        slot = vfuns++;
                    }
                } else {
                    slot = vfuns++;
                }
            }
            methods = new MethEnv(mods, type, id, params, body,
                                  this, slot, size, methods);
        }
    }

    /** Look for a field by name in this class.
     */
    public FieldEnv findField(String name) {
        FieldEnv env = FieldEnv.find(name, fields);
        if (env == null && extendsType != null) {
            env = extendsType.isClass().findField(name);
        }
        return env;
    }

    /** Look for a method by name in this class.
     */
    public MethEnv findMethod(String name) {
        MethEnv env = MethEnv.find(name, methods);
        if (env == null && extendsType != null) {
            env = extendsType.isClass().findMethod(name);
        }
        return env;
    }

    /** Check (static analysis) the definitions of the fields and
     *  methods in this class.
     */
    public void checkMembers(Context ctxt) {
        ctxt.setCurrClass(this);
        FieldEnv.checkFields(ctxt, fields);
        MethEnv.checkMethods(ctxt, methods);
        ctxt.setCurrClass(null);
    }

    /** Generate code for each of the fields and methods in this class.
     */
    public void compile(Assembly a) {
        FieldEnv.compileFields(a, fields);
        MethEnv.compileMethods(a, methods);
        a.emitVTable(this, width, vtable);
    }

    private TypeRef [] llvmFields() {
        return llvmType().getStructElementTypes();
    }

    public void llvmGenTypes(LLVM l) {
        if (methods != null) {
            for (MethEnv m : methods) {
                m.llvmGenTypes(l);
            }
        }
        llvmType();

        ArrayList<org.llvm.Value> vtable_inits = new ArrayList<org.llvm.Value>();
        for (MethEnv m : vtable) {
            vtable_inits.add(m.getFunctionVal());
        }

        llvmVtableLoc = l.getModule().addGlobal(getLLVMVtable(),
                                                id.getName() + "_vtable_loc");
        
        llvmVtableLoc.setInitializer(org.llvm.Value.constNamedStruct(getLLVMVtable(),
                                     vtable_inits.toArray(new org.llvm.Value[0])));
    }

    public TypeRef llvmType() {
        if (llvmType == null) {
            llvmType = TypeRef.structTypeNamed(id.getName());
            ArrayList<TypeRef> llvm_fields;
            if (extendsType != null) {
                TypeRef [] fields = ((ClassType)extendsType).llvmFields();
                /* remove the vtable entry for the parent type */
                llvm_fields = new ArrayList(Arrays.asList(fields));
                llvm_fields.remove(0);
            } else {
                llvm_fields = new ArrayList<TypeRef>();
            }
            /* insert vtable entry for current */
            llvm_fields.add(0, getLLVMVtable().pointerType());

            if (fields != null) {
                for (FieldEnv f : fields) {
                    TypeRef t;
                    if (f.getType() == this) {
                        t = llvmType.pointerType();
                    } else {
                        t = f.llvmType();
                        if (f.getType().isClass() != null) {
                            t = t.pointerType();
                        }
                    }
                    llvm_fields.add(t);
                }
            }
            TypeRef.structSetBody(llvmType, llvm_fields, false);
        }
        return llvmType;
    }

    private TypeRef getLLVMVtable() {
        if (llvmVtable == null) {
            llvmVtable = TypeRef.structTypeNamed(id.getName() + "_vtable");
            ArrayList<TypeRef> vtable_items = new ArrayList<TypeRef>();

            for (MethEnv m : vtable) {
                TypeRef t = m.llvmType().pointerType();
                vtable_items.add(t);
            }

            TypeRef.structSetBody(llvmVtable, vtable_items, false);
        }
        return llvmVtable;
    }

    public void llvmGen(LLVM l) {
        if (fields != null) {
            Builder b = l.getBuilder();
            l.getBuilder().positionBuilderAtEnd(l.getStaticInit());
            for (FieldEnv f : fields) {
                if (f.isStatic()) {
                    System.out.println("static field name: " + f.getName());
                    org.llvm.Value v = l.getModule().addGlobal(f.llvmType(),
                                       f.getOwner() + "." + f.getName());
                    
                    l.setNamedValue(f.getOwner() + "." + f.getName(), v);
                    
                    //
                    // Problems with initializers.  The following are two approaches for setting 
                    // the global initializer. 
                    //
                    // Global variable initializer type does not match global variable type!
                    // %Hint* @TestObj.h
                    //
                    // For the line below.  Using zeroinitializer instead of null (almost) works (using 2nd example)
                    // @TestObj.h = global %Hint null
                    // v.setInitializer(f.getType().defaultValue());
                    // This approach uses zeroinitializer.  --LVVM output still complains but the llvm-as will take the
                    // assembly listing and compile it without complaint?
                    //org.llvm.Value init = TypeRef.structTypeNamed(f.getName()).constNull();
                    //v.setInitializer(init);
                    
                    // set the gcroot for this var for later garbage collection
                    //org.llvm.Value res = b.buildBitCast(l.getNamedValue(f.getOwner() + "." + f.getName()), TypeRef.int8Type().pointerType().pointerType(), "gctmp");
                    //org.llvm.Value meta = TypeRef.int8Type().pointerType().constNull();  // TODO: replace with type data
                    //org.llvm.Value [] args = {res, meta};
                    //org.llvm.Value gc = b.buildCall(l.getGCRoot(), "", args);     
                }
            }
        }

        if (methods != null) {
            for (MethEnv m : methods) {
                m.llvmGenMethod(l);
            }
        }
    }
    /** Construct an object for a new object of this class.
     */
    public ObjValue newObject() {
        return new ObjValue(this, width);
    }

    /** Call the virtual method associated with a particular slot
     *  in this class.
     */
    public Value call(State st, int slot) {
        return st.call(vtable[slot]);
    }

    public org.llvm.Value defaultValue() {
        org.llvm.Value v = llvmType().pointerType().constPointerNull();
        v.setValueName("null");
        return v;
    }
}

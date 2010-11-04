package com.xtremelabs.robolectric;

import javassist.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AndroidTranslator implements Translator {
    /**
     * IMPORTANT -- increment this number when the bytecode generated for modified classes changes
     * so the cache file can be invalidated.
     */
    public static final int CACHE_VERSION = 6;

    private static final List<AndroidTranslator> INSTANCES = new ArrayList<AndroidTranslator>();

    private static boolean callDirectly = false; // todo: move this and make it MT-safe

    private int index;
    private ClassHandler classHandler;
    private ClassCache classCache;

    public AndroidTranslator(ClassHandler classHandler, ClassCache classCache) {
        this.classHandler = classHandler;
        this.classCache = classCache;
        index = addInstance(this);
    }

    synchronized static private int addInstance(AndroidTranslator androidTranslator) {
        INSTANCES.add(androidTranslator);
        return INSTANCES.size() - 1;
    }

    synchronized static public AndroidTranslator get(int index) {
        return INSTANCES.get(index);
    }


    public static <T> T directlyOn(T shadowedObject) {
        callDirectly = true;
        return shadowedObject;
    }

    public static boolean shouldCallDirectly() {
        if (callDirectly) {
            callDirectly = false;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void start(ClassPool classPool) throws NotFoundException, CannotCompileException {
    }

    @Override
    public void onLoad(ClassPool classPool, String className) throws NotFoundException, CannotCompileException {
        if (classCache.isWriting()) {
            throw new IllegalStateException("shouldn't be modifying bytecode after we've started writing cache! class=" + className);
        }

        boolean needsStripping =
                className.startsWith("android.")
                        || className.startsWith("org.apache.http")
                        || className.startsWith("com.google.android.");

        CtClass ctClass = classPool.get(className);
        if (needsStripping) {
            int modifiers = ctClass.getModifiers();
            if (Modifier.isFinal(modifiers)) {
                ctClass.setModifiers(modifiers & ~Modifier.FINAL);
            }

            if (ctClass.isInterface()) return;

            classHandler.instrument(ctClass);

            fixConstructors(ctClass);
            fixMethods(ctClass);

            try {
                classCache.addClass(className, ctClass.toBytecode());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void addBypassShadowField(CtClass ctClass, String fieldName) {
        try {
            try {
                ctClass.getField(fieldName);
            } catch (NotFoundException e) {
                CtField field = new CtField(CtClass.booleanType, fieldName, ctClass);
                field.setModifiers(java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC);
                ctClass.addField(field);
            }
        } catch (CannotCompileException e) {
            throw new RuntimeException(e);
        }
    }

    private void fixConstructors(CtClass ctClass) throws CannotCompileException, NotFoundException {
        boolean hasDefault = false;

        for (CtConstructor ctConstructor : ctClass.getConstructors()) {
            try {
                fixConstructor(ctClass, hasDefault, ctConstructor);

                if (ctConstructor.getParameterTypes().length == 0) {
                    hasDefault = true;
                }
            } catch (Exception e) {
                throw new RuntimeException("problem instrumenting " + ctConstructor, e);
            }
        }

        if (!hasDefault) {
            String methodBody = generateConstructorBody(ctClass, new CtClass[0]);
            ctClass.addConstructor(CtNewConstructor.make(new CtClass[0], new CtClass[0], "{\n" + methodBody + "}\n", ctClass));
        }
    }

    private boolean fixConstructor(CtClass ctClass, boolean needsDefault, CtConstructor ctConstructor) throws NotFoundException, CannotCompileException {
        String methodBody = generateConstructorBody(ctClass, ctConstructor.getParameterTypes());
        ctConstructor.setBody("{\n" + methodBody + "}\n");
        return needsDefault;
    }

    private String generateConstructorBody(CtClass ctClass, CtClass[] parameterTypes) throws NotFoundException {
        return generateMethodBody(ctClass,
                new CtMethod(CtClass.voidType, "<init>", parameterTypes, ctClass),
                CtClass.voidType,
                Type.VOID,
                false);
    }

    private void fixMethods(CtClass ctClass) throws NotFoundException, CannotCompileException {
        for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
            fixMethod(ctClass, ctMethod);
        }
        CtMethod equalsMethod = ctClass.getMethod("equals", "(Ljava/lang/Object;)Z");
        CtMethod hashCodeMethod = ctClass.getMethod("hashCode", "()I");
        CtMethod toStringMethod = ctClass.getMethod("toString", "()Ljava/lang/String;");

        fixMethod(ctClass, equalsMethod);
        fixMethod(ctClass, hashCodeMethod);
        fixMethod(ctClass, toStringMethod);
    }

    private String describe(CtMethod ctMethod) throws NotFoundException {
        return Modifier.toString(ctMethod.getModifiers()) + " " + ctMethod.getReturnType().getSimpleName() + " " + ctMethod.getLongName();
    }

    private void fixMethod(CtClass ctClass, CtMethod ctMethod) throws NotFoundException {
        String describeBefore = describe(ctMethod);
        try {
            CtClass declaringClass = ctMethod.getDeclaringClass();
            int originalModifiers = ctMethod.getModifiers();

            boolean wasNative = Modifier.isNative(originalModifiers);
            boolean wasFinal = Modifier.isFinal(originalModifiers);
            boolean wasAbstract = Modifier.isAbstract(originalModifiers);
            boolean wasDeclaredInClass = declaringClass.equals(ctClass);

            if (wasFinal && ctClass.isEnum()) {
                return;
            }

            int newModifiers = originalModifiers;
            if (wasNative) {
                newModifiers = Modifier.clear(newModifiers, Modifier.NATIVE);
            }
            if (wasFinal) {
                newModifiers = Modifier.clear(newModifiers, Modifier.FINAL);
            }
            if (wasDeclaredInClass) {
                ctMethod.setModifiers(newModifiers);
            }

            CtClass returnCtClass = ctMethod.getReturnType();
            Type returnType = Type.find(returnCtClass);

            String methodName = ctMethod.getName();
            CtClass[] paramTypes = ctMethod.getParameterTypes();

//            if (!isAbstract) {
//                if (methodName.startsWith("set") && paramTypes.length == 1) {
//                    String fieldName = "__" + methodName.substring(3);
//                    if (declareField(ctClass, fieldName, paramTypes[0])) {
//                        methodBody = fieldName + " = $1;\n" + methodBody;
//                    }
//                } else if (methodName.startsWith("get") && paramTypes.length == 0) {
//                    String fieldName = "__" + methodName.substring(3);
//                    if (declareField(ctClass, fieldName, returnType)) {
//                        methodBody = "return " + fieldName + ";\n";
//                    }
//                }
//            }

            boolean isStatic = Modifier.isStatic(originalModifiers);
            String methodBody = generateMethodBody(ctClass, ctMethod, wasNative, wasAbstract, returnCtClass, returnType, isStatic);

            if (!wasDeclaredInClass) {
                CtMethod newMethod = makeNewMethod(ctClass, ctMethod, returnCtClass, methodName, paramTypes, "{\n" + methodBody + generateCallToSuper(methodName, paramTypes) + "\n}");
                newMethod.setModifiers(newModifiers);
                ctClass.addMethod(newMethod);
            } else if (wasAbstract || wasNative) {
                CtMethod newMethod = makeNewMethod(ctClass, ctMethod, returnCtClass, methodName, paramTypes, "{\n" + methodBody + "\n}");
                ctMethod.setBody(newMethod, null);
            } else {
                ctMethod.insertBefore("{\n" + methodBody + "}\n");
            }
        } catch (Exception e) {
            throw new RuntimeException("problem instrumenting " + describeBefore, e);
        }
    }

    private CtMethod makeNewMethod(CtClass ctClass, CtMethod ctMethod, CtClass returnCtClass, String methodName, CtClass[] paramTypes, String methodBody) throws CannotCompileException, NotFoundException {
        return CtNewMethod.make(
                ctMethod.getModifiers(),
                returnCtClass,
                methodName,
                paramTypes,
                ctMethod.getExceptionTypes(),
                methodBody,
                ctClass);
    }

    public String generateCallToSuper(String methodName, CtClass[] paramTypes) {
        return "return super." + methodName + "(" + makeParameterReplacementList(paramTypes.length) + ");";
    }

    public String makeParameterReplacementList(int length) {
        if (length == 0) {
            return "";
        }

        String parameterReplacementList = "$1";
        for (int i = 2; i <= length; ++i) {
            parameterReplacementList += ", $" + i;
        }
        return parameterReplacementList;
    }

    private String generateMethodBody(CtClass ctClass, CtMethod ctMethod, boolean wasNative, boolean wasAbstract, CtClass returnCtClass, Type returnType, boolean aStatic) throws NotFoundException {
        String methodBody;
        if (wasAbstract) {
            methodBody = returnType.isVoid() ? "" : "return " + returnType.defaultReturnString() + ";";
        } else {
            methodBody = generateMethodBody(ctClass, ctMethod, returnCtClass, returnType, aStatic);
        }

        if (wasNative) {
            methodBody += returnType.isVoid() ? "" : "return " + returnType.defaultReturnString() + ";";
        }
        return methodBody;
    }

    public String generateMethodBody(CtClass ctClass, CtMethod ctMethod, CtClass returnCtClass, Type returnType, boolean aStatic) throws NotFoundException {
        boolean returnsVoid = returnType.isVoid();
        String className = ctClass.getName();

        String methodBody;
        StringBuilder buf = new StringBuilder();
        buf.append("if (!").append(AndroidTranslator.class.getName()).append(".shouldCallDirectly()) {\n");

        if (!returnsVoid) {
            buf.append("Object x = ");
        }
        buf.append(AndroidTranslator.class.getName());
        buf.append(".get(");
        buf.append(getIndex());
        buf.append(").methodInvoked(\n  ");
        buf.append(className);
        buf.append(".class, \"");
        buf.append(ctMethod.getName());
        buf.append("\", ");
        if (!aStatic) {
            buf.append("this");
        } else {
            buf.append("null");
        }
        buf.append(", ");

        appendParamTypeArray(buf, ctMethod);
        buf.append(", ");
        appendParamArray(buf, ctMethod);

        buf.append(")");
        buf.append(";\n");

        if (!returnsVoid) {
            buf.append("if (x != null) return ((");
            buf.append(returnType.nonPrimitiveClassName(returnCtClass));
            buf.append(") x)");
            buf.append(returnType.unboxString());
            buf.append(";\n");
            buf.append("return ");
            buf.append(returnType.defaultReturnString());
            buf.append(";\n");
        } else {
            buf.append("return;\n");
        }

        buf.append("}\n");

        methodBody = buf.toString();
        return methodBody;
    }

    protected int getIndex() {
        return index;
    }

    private void appendParamTypeArray(StringBuilder buf, CtMethod ctMethod) throws NotFoundException {
        CtClass[] parameterTypes = ctMethod.getParameterTypes();
        if (parameterTypes.length == 0) {
            buf.append("new String[0]");
        } else {
            buf.append("new String[] {");
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append("\"");
                CtClass parameterType = parameterTypes[i];
                buf.append(parameterType.getName());
                buf.append("\"");
            }
            buf.append("}");
        }
    }

    private void appendParamArray(StringBuilder buf, CtMethod ctMethod) throws NotFoundException {
        int parameterCount = ctMethod.getParameterTypes().length;
        if (parameterCount == 0) {
            buf.append("new Object[0]");
        } else {
            buf.append("new Object[] {");
            for (int i = 0; i < parameterCount; i++) {
                if (i > 0) buf.append(", ");
                buf.append(AndroidTranslator.class.getName());
                buf.append(".autobox(");
                buf.append("$").append(i + 1);
                buf.append(")");
            }
            buf.append("}");
        }
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public Object methodInvoked(Class clazz, String methodName, Object instance, String[] paramTypes, Object[] params) {
        return classHandler.methodInvoked(clazz, methodName, instance, paramTypes, params);
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(Object o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(boolean o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(byte o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(char o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(short o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(int o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(long o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(float o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(double o) {
        return o;
    }

    private boolean declareField(CtClass ctClass, String fieldName, CtClass fieldType) throws CannotCompileException, NotFoundException {
        CtMethod ctMethod = getMethod(ctClass, "get" + fieldName, "");
        if (ctMethod == null) {
            return false;
        }
        CtClass getterFieldType = ctMethod.getReturnType();

        if (!getterFieldType.equals(fieldType)) {
            return false;
        }

        if (getField(ctClass, fieldName) == null) {
            CtField field = new CtField(fieldType, fieldName, ctClass);
            field.setModifiers(Modifier.PRIVATE);
            ctClass.addField(field);
        }

        return true;
    }

    private CtField getField(CtClass ctClass, String fieldName) {
        try {
            return ctClass.getField(fieldName);
        } catch (NotFoundException e) {
            return null;
        }
    }

    private CtMethod getMethod(CtClass ctClass, String methodName, String desc) {
        try {
            return ctClass.getMethod(methodName, desc);
        } catch (NotFoundException e) {
            return null;
        }
    }
}

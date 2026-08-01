package com.sickworm.intellij.jugg.tools.stubapi;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Generates compile-only API class files from the dependencies referenced by a compiled JAR. */
public final class StubApiGenerator {
    private static final int ASM_VERSION = Opcodes.ASM9;

    private StubApiGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Map<String, ClassSource> classPath = indexClassPath(arguments.classPath);
        Map<String, byte[]> stubs = createStubs(collectReferences(arguments.inputJar), classPath);
        writeJar(arguments.outputJar, stubs, classPath);
        writeMetadata(arguments, classPath, stubs.size());
        System.out.println("Generated " + stubs.size() + " stub classes at " + arguments.outputJar);
    }

    private static Map<String, ClassSource> indexClassPath(Path classPath) throws IOException {
        if (!Files.isDirectory(classPath)) {
            throw new IllegalArgumentException("Classpath directory does not exist: " + classPath);
        }
        Map<String, ClassSource> result = new LinkedHashMap<>();
        try (var paths = Files.walk(classPath)) {
            List<Path> jars = new ArrayList<>();
            paths.filter(path -> path.toString().endsWith(".jar")).sorted().forEach(jars::add);
            for (Path jar : jars) {
                try (JarFile jarFile = new JarFile(jar.toFile())) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.getName().endsWith(".class") && !entry.getName().equals("module-info.class")) {
                            String name = entry.getName().substring(0, entry.getName().length() - 6);
                            if (!result.containsKey(name)) {
                                try (InputStream input = jarFile.getInputStream(entry)) {
                                    result.put(name, new ClassSource(jar, input.readAllBytes()));
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Set<String> collectReferences(Path inputJar) throws IOException {
        Set<String> result = new HashSet<>();
        try (JarFile jarFile = new JarFile(inputJar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream input = jarFile.getInputStream(entry)) {
                    new ClassReader(input).accept(new ReferenceCollector(result), ClassReader.SKIP_FRAMES);
                }
            }
        }
        return result;
    }

    private static Map<String, byte[]> createStubs(Set<String> roots, Map<String, ClassSource> classPath)
            throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (result.containsKey(name)) {
                continue;
            }
            ClassSource source = classPath.get(name);
            if (source == null) {
                continue;
            }
            byte[] original = source.read();
            ClassReader reader = new ClassReader(original);
            Set<String> declarationTypes = new HashSet<>();
            reader.accept(new ReferenceCollector(declarationTypes), ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            pending.addAll(declarationTypes);
            int nestedSeparator = name.lastIndexOf('$');
            if (nestedSeparator > 0) {
                pending.add(name.substring(0, nestedSeparator));
            }
            String nestedPrefix = name + '$';
            classPath.keySet().stream().filter(candidate -> candidate.startsWith(nestedPrefix)).forEach(pending::add);
            if (reader.getSuperName() != null) {
                pending.add(reader.getSuperName());
            }
            for (String interfaceName : reader.getInterfaces()) {
                pending.add(interfaceName);
            }
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new StubClassVisitor(writer, reader.getSuperName()), 0);
            result.put(name, writer.toByteArray());
        }
        return result;
    }

    private static void writeJar(Path output, Map<String, byte[]> classes, Map<String, ClassSource> classPath)
            throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        List<String> names = new ArrayList<>(classes.keySet());
        names.sort(Comparator.naturalOrder());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            Set<String> writtenEntries = new HashSet<>();
            for (String name : names) {
                String entryName = name + ".class";
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0);
                jar.putNextEntry(entry);
                jar.write(classes.get(name));
                jar.closeEntry();
                writtenEntries.add(entryName);
            }
            Set<Path> sourceJars = new HashSet<>();
            classPath.values().forEach(source -> sourceJars.add(source.jar));
            for (Path sourceJar : sourceJars) {
                try (JarFile jarFile = new JarFile(sourceJar.toFile())) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry sourceEntry = entries.nextElement();
                        if (!sourceEntry.getName().endsWith(".kotlin_module")
                                || !writtenEntries.add(sourceEntry.getName())) {
                            continue;
                        }
                        JarEntry entry = new JarEntry(sourceEntry.getName());
                        entry.setTime(0);
                        jar.putNextEntry(entry);
                        try (InputStream input = jarFile.getInputStream(sourceEntry)) {
                            input.transferTo(jar);
                        }
                        jar.closeEntry();
                    }
                }
            }
        }
    }

    private static void writeMetadata(Arguments arguments, Map<String, ClassSource> classPath, int count)
            throws Exception {
        Path metadata = arguments.outputJar.resolveSibling("stubapi.properties");
        Set<Path> usedJars = new HashSet<>();
        for (ClassSource source : classPath.values()) {
            usedJars.add(source.jar);
        }
        List<String> lines = new ArrayList<>();
        lines.add("input=" + arguments.inputJar.getFileName());
        lines.add("inputSha256=" + sha256(arguments.inputJar));
        lines.add("classCount=" + count);
        List<Path> sortedJars = new ArrayList<>(usedJars);
        sortedJars.sort(Comparator.naturalOrder());
        for (int index = 0; index < sortedJars.size(); index++) {
            Path jar = sortedJars.get(index);
            lines.add("classpathJar." + index + "=" + jar.getFileName() + ",sha256=" + sha256(jar));
        }
        Files.write(metadata, lines);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static void addType(Set<String> classes, Type type) {
        if (type.getSort() == Type.ARRAY) {
            addType(classes, type.getElementType());
        } else if (type.getSort() == Type.OBJECT) {
            classes.add(type.getInternalName());
        } else if (type.getSort() == Type.METHOD) {
            addType(classes, type.getReturnType());
            for (Type argument : type.getArgumentTypes()) {
                addType(classes, argument);
            }
        }
    }

    private static void addSignature(Set<String> classes, String signature) {
        if (signature == null) {
            return;
        }
        new SignatureReader(signature).accept(new SignatureVisitor(ASM_VERSION) {
            @Override
            public void visitClassType(String name) {
                classes.add(name);
            }
        });
    }

    private static final class ReferenceCollector extends ClassVisitor {
        private final Set<String> classes;

        private ReferenceCollector(Set<String> classes) {
            super(ASM_VERSION);
            this.classes = classes;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            if (superName != null) {
                classes.add(superName);
            }
            for (String interfaceName : interfaces) {
                classes.add(interfaceName);
            }
            addSignature(classes, signature);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            addType(classes, Type.getType(descriptor));
            addSignature(classes, signature);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            addType(classes, Type.getMethodType(descriptor));
            addSignature(classes, signature);
            return new MethodVisitor(ASM_VERSION) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    classes.add(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    classes.add(owner);
                    addType(classes, Type.getType(descriptor));
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                            boolean isInterface) {
                    classes.add(owner);
                    addType(classes, Type.getMethodType(descriptor));
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type) {
                        addType(classes, (Type) value);
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                                   Object... bootstrapMethodArguments) {
                    addType(classes, Type.getMethodType(descriptor));
                    for (Object argument : bootstrapMethodArguments) {
                        if (argument instanceof Type) {
                            addType(classes, (Type) argument);
                        } else if (argument instanceof Handle) {
                            classes.add(((Handle) argument).getOwner());
                        }
                    }
                }

                @Override
                public void visitLocalVariable(String name, String descriptor, String signature, Label start,
                                               Label end, int index) {
                    addType(classes, Type.getType(descriptor));
                    addSignature(classes, signature);
                }
            };
        }
    }

    private static final class StubClassVisitor extends ClassVisitor {
        private final String superName;

        private StubClassVisitor(ClassVisitor delegate, String superName) {
            super(ASM_VERSION, delegate);
            this.superName = superName;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return method;
            }
            return new StubMethodVisitor(method, superName, name, descriptor);
        }
    }

    /** Preserves method declarations and annotations while replacing executable code. */
    private static final class StubMethodVisitor extends MethodVisitor {
        private final String superName;
        private final String name;
        private final String descriptor;

        private StubMethodVisitor(MethodVisitor delegate, String superName, String name, String descriptor) {
            super(ASM_VERSION, delegate);
            this.superName = superName;
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public void visitCode() {
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        }

        @Override
        public void visitInsn(int opcode) {
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
        }

        @Override
        public void visitLabel(Label label) {
        }

        @Override
        public void visitLdcInsn(Object value) {
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label defaultLabel, Label... labels) {
        }

        @Override
        public void visitLookupSwitchInsn(Label defaultLabel, int[] keys, Label[] labels) {
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String descriptor,
                                                     boolean visible) {
            return null;
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String descriptor,
                                                         boolean visible) {
            return null;
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start,
                                       Label end, int index) {
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start,
                                                              Label[] end, int[] index, String descriptor,
                                                              boolean visible) {
            return null;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
        }

        @Override
        public void visitEnd() {
            mv.visitCode();
            if (name.equals("<init>")) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        superName == null ? "java/lang/Object" : superName, "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
            } else {
                Type returnType = Type.getReturnType(descriptor);
                if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                } else if (returnType.getSort() == Type.LONG) {
                    mv.visitInsn(Opcodes.LCONST_0);
                } else if (returnType.getSort() == Type.FLOAT) {
                    mv.visitInsn(Opcodes.FCONST_0);
                } else if (returnType.getSort() == Type.DOUBLE) {
                    mv.visitInsn(Opcodes.DCONST_0);
                } else if (returnType.getSort() != Type.VOID) {
                    mv.visitInsn(Opcodes.ICONST_0);
                }
                mv.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
                mv.visitMaxs(2, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
            }
            mv.visitEnd();
        }
    }

    private static final class ClassSource {
        private final Path jar;
        private final byte[] bytes;

        private ClassSource(Path jar, byte[] bytes) {
            this.jar = jar;
            this.bytes = bytes;
        }

        private byte[] read() {
            return bytes;
        }
    }

    private static final class Arguments {
        private final Path inputJar;
        private final Path classPath;
        private final Path outputJar;

        private Arguments(Path inputJar, Path classPath, Path outputJar) {
            this.inputJar = inputJar;
            this.classPath = classPath;
            this.outputJar = outputJar;
        }

        private static Arguments parse(String[] args) {
            Map<String, Path> values = new HashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length) {
                    throw usage();
                }
                values.put(args[index], Path.of(args[index + 1]));
            }
            Path input = values.get("--input");
            Path classPath = values.get("--classpath");
            Path output = values.get("--output");
            if (input == null || classPath == null || output == null) {
                throw usage();
            }
            return new Arguments(input, classPath, output);
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "Usage: --input <compiled.jar> --classpath <studio-jar-dir> --output <stubapi.jar>");
        }
    }
}

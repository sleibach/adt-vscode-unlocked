import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Self-contained patcher for the SAP "ABAP Development Tools" VS Code language
 * server jar (com.sap.adt.ls_*.jar). Applies, idempotently:
 *
 *  1. listSystemConfigurations  (AdtLsDestinationService): stop dropping
 *     non-SSO RFC systems so they appear in the destination wizard.
 *  2. lambda$1 (AdtLsLogonService): replace the non-SSO
 *     `throw new UnsupportedOperationException()` with a call to
 *     com.sap.adt.patch.BasicAuthRfcLogon.logon(...).
 *  3. Inject com/sap/adt/patch/BasicAuthRfcLogon.class into the jar.
 *
 * Only the two target methods are rewritten; every other method/class is copied
 * byte-for-byte (reader-based ClassWriter), so untouched code keeps its original
 * stack-map frames and verifies unchanged.
 *
 * Usage: java -cp "adt-unlock.jar:<asm>:<asm-tree>" AdtUnlock <path-to-ls-jar>
 */
public class AdtUnlock {

    static final String LOGON_CLASS = "com/sap/adt/ls/internal/project/AdtLsLogonService";
    static final String DEST_CLASS  = "com/sap/adt/ls/internal/project/AdtLsDestinationService";
    static final String HELPER      = "com/sap/adt/patch/BasicAuthRfcLogon";

    // Object-type support gate. isObjectTypeSupported() decides whether a repo-tree
    // object opens as real source or as the read-only "<name>.<type>.jsonc" fallback.
    static final String OBJTYPE_CLASS  = "com/sap/adt/ls/internal/objecttypeinfo/AdtLsObjectTypeUtil";
    static final String OBJTYPE_METHOD = "isObjectTypeSupported";
    static final String OBJTYPE_DESC   =
        "(Lcom/sap/adt/tools/core/objecttype/IAdtObjectTypeInfo;"
        + "Lorg/eclipse/core/resources/IProject;)Z";
    static final String OBJTYPE_HELPER = "com/sap/adt/patch/ObjectTypeProbe";

    // Helper classes to bundle/inject into the patched jar.
    static final String[] HELPERS = { HELPER, OBJTYPE_HELPER };

    static final String LOGON_METHOD = "lambda$1";
    static final String LOGON_DESC =
        "(Ljava/lang/String;Lorg/eclipse/core/runtime/IProgressMonitor;"
        + "Lcom/sap/adt/destinations/model/IDestinationData;"
        + "Lcom/sap/adt/destinations/logon/IAdtLogonService;)"
        + "Lorg/eclipse/core/runtime/IStatus;";

    interface MethodXform { boolean apply(MethodNode mn); }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: AdtUnlock <path-to-com.sap.adt.ls_*.jar>");
            System.exit(2);
        }
        Path lsJar = Paths.get(args[0]).toAbsolutePath();
        if (!Files.isRegularFile(lsJar)) {
            System.err.println("Not found: " + lsJar);
            System.exit(2);
        }
        Path pluginsDir = lsJar.getParent();

        List<URL> urls = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path p : ds) urls.add(p.toUri().toURL());
        }
        urls.add(AdtUnlock.class.getProtectionDomain().getCodeSource().getLocation());
        final URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), AdtUnlock.class.getClassLoader());

        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, Long> times = new HashMap<>();
        try (JarFile jf = new JarFile(lsJar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory()) { entries.put(e.getName(), null); continue; }
                try (InputStream is = jf.getInputStream(e)) {
                    entries.put(e.getName(), is.readAllBytes());
                }
                times.put(e.getName(), e.getTime());
            }
        }

        byte[] logon = entries.get(LOGON_CLASS + ".class");
        boolean logonPatched = false;
        if (logon != null) {
            byte[] out = transformClass(logon, LOGON_METHOD, LOGON_DESC, AdtUnlock::patchLogon, cl);
            if (out != null) { entries.put(LOGON_CLASS + ".class", out); logonPatched = true; }
        }

        byte[] dest = entries.get(DEST_CLASS + ".class");
        boolean destPatched = false;
        if (dest != null) {
            byte[] out = transformClass(dest, "listSystemConfigurations", null, AdtUnlock::patchDestinations, cl);
            if (out != null) { entries.put(DEST_CLASS + ".class", out); destPatched = true; }
        }

        byte[] objType = entries.get(OBJTYPE_CLASS + ".class");
        boolean objTypePatched = false;
        if (objType != null) {
            byte[] out = transformClass(objType, OBJTYPE_METHOD, OBJTYPE_DESC, AdtUnlock::patchObjectTypeSupported, cl);
            if (out != null) { entries.put(OBJTYPE_CLASS + ".class", out); objTypePatched = true; }
        }

        boolean helperAdded = false;
        for (String helper : HELPERS) {
            byte[] helperBytes;
            try (InputStream is = AdtUnlock.class.getResourceAsStream("/" + helper + ".class")) {
                if (is == null) throw new IllegalStateException("Helper class not bundled in tool jar: " + helper);
                helperBytes = is.readAllBytes();
            }
            if (!entries.containsKey(helper + ".class")) helperAdded = true;
            entries.put(helper + ".class", helperBytes);
        }

        Path tmp = Files.createTempFile(pluginsDir, "adt-unlock-", ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tmp))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                JarEntry je = new JarEntry(e.getKey());
                Long t = times.get(e.getKey());
                if (t != null) je.setTime(t);
                jos.putNextEntry(je);
                if (e.getValue() != null) jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        Files.move(tmp, lsJar, StandardCopyOption.REPLACE_EXISTING);

        System.out.println("listSystemConfigurations (show non-SSO RFC): " + (destPatched ? "patched" : "already patched / not found"));
        System.out.println("RFC basic-auth logon (lambda$1):           " + (logonPatched ? "patched" : "already patched / not found"));
        System.out.println("isObjectTypeSupported (object-type probe): " + (objTypePatched ? "patched" : "already patched / not found"));
        System.out.println("Injected helpers:                          " + (helperAdded ? "added" : "updated"));
        System.out.println("OK: " + lsJar);
    }

    /** Replace NEW/DUP/INVOKESPECIAL/ATHROW of UnsupportedOperationException with a call to the helper. */
    static boolean patchLogon(MethodNode mn) {
        for (AbstractInsnNode p = mn.instructions.getFirst(); p != null; p = p.getNext()) {
            if (p.getOpcode() == Opcodes.NEW
                && p instanceof TypeInsnNode tin
                && "java/lang/UnsupportedOperationException".equals(tin.desc)) {
                AbstractInsnNode a = p.getNext(), b = a == null ? null : a.getNext(), c = b == null ? null : b.getNext();
                if (a != null && a.getOpcode() == Opcodes.DUP
                    && b instanceof MethodInsnNode mi && b.getOpcode() == Opcodes.INVOKESPECIAL
                    && "java/lang/UnsupportedOperationException".equals(mi.owner) && "<init>".equals(mi.name)
                    && c != null && c.getOpcode() == Opcodes.ATHROW) {
                    InsnList repl = new InsnList();
                    repl.add(new VarInsnNode(Opcodes.ALOAD, 3)); // dest
                    repl.add(new VarInsnNode(Opcodes.ALOAD, 4)); // logonService
                    repl.add(new VarInsnNode(Opcodes.ALOAD, 2)); // monitor
                    repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "logon",
                        "(Lcom/sap/adt/destinations/model/IDestinationData;"
                        + "Lcom/sap/adt/destinations/logon/IAdtLogonService;"
                        + "Lorg/eclipse/core/runtime/IProgressMonitor;)"
                        + "Lorg/eclipse/core/runtime/IStatus;", false));
                    repl.add(new VarInsnNode(Opcodes.ASTORE, 7));
                    repl.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                    repl.add(new VarInsnNode(Opcodes.ALOAD, 1)); // destId
                    repl.add(new InsnNode(Opcodes.ICONST_0));
                    repl.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOGON_CLASS, "dismantleLogon",
                        "(Ljava/lang/String;Z)V", false));
                    repl.add(new VarInsnNode(Opcodes.ALOAD, 7));
                    repl.add(new InsnNode(Opcodes.ARETURN));
                    mn.instructions.insertBefore(p, repl);
                    mn.instructions.remove(a);
                    mn.instructions.remove(b);
                    mn.instructions.remove(c);
                    mn.instructions.remove(p);
                    return true;
                }
            }
        }
        return false;
    }

    /** Force the isSSOEnabled() guard (the one followed by IFNE) to be true. */
    static boolean patchDestinations(MethodNode mn) {
        for (AbstractInsnNode p = mn.instructions.getFirst(); p != null; p = p.getNext()) {
            if (p.getOpcode() == Opcodes.INVOKEINTERFACE
                && p instanceof MethodInsnNode mi
                && mi.owner.endsWith("ISystemConfiguration")
                && "isSSOEnabled".equals(mi.name) && "()Z".equals(mi.desc)) {
                AbstractInsnNode nxt = nextReal(p);
                if (nxt != null && nxt.getOpcode() == Opcodes.IFNE) {
                    InsnList repl = new InsnList();
                    repl.add(new InsnNode(Opcodes.POP));      // drop the ISystemConfiguration ref
                    repl.add(new InsnNode(Opcodes.ICONST_1));  // push true
                    mn.instructions.insertBefore(p, repl);
                    mn.instructions.remove(p);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Replace the whole body of isObjectTypeSupported(info, project) with a
     * delegation to ObjectTypeProbe.isSupported(this, info, project). Returns
     * false (no change) if already delegating, for idempotency.
     */
    static boolean patchObjectTypeSupported(MethodNode mn) {
        for (AbstractInsnNode p = mn.instructions.getFirst(); p != null; p = p.getNext()) {
            if (p instanceof MethodInsnNode mi && p.getOpcode() == Opcodes.INVOKESTATIC
                && OBJTYPE_HELPER.equals(mi.owner) && "isSupported".equals(mi.name)) {
                return false; // already patched
            }
        }
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (AdtLsObjectTypeUtil)
        body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // info
        body.add(new VarInsnNode(Opcodes.ALOAD, 2)); // project
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OBJTYPE_HELPER, "isSupported",
            "(L" + OBJTYPE_CLASS + ";"
            + "Lcom/sap/adt/tools/core/objecttype/IAdtObjectTypeInfo;"
            + "Lorg/eclipse/core/resources/IProject;)Z", false));
        body.add(new InsnNode(Opcodes.IRETURN));
        mn.instructions.clear();
        mn.tryCatchBlocks.clear();
        if (mn.localVariables != null) mn.localVariables.clear();
        mn.instructions.add(body);
        return true;
    }

    static AbstractInsnNode nextReal(AbstractInsnNode n) {
        for (AbstractInsnNode p = n.getNext(); p != null; p = p.getNext()) {
            if (p instanceof LabelNode || p instanceof LineNumberNode || p instanceof FrameNode) continue;
            return p;
        }
        return null;
    }

    /**
     * Rewrite only the named method; copy every other method verbatim
     * (reader-based writer) so untouched frames are preserved and not
     * recomputed. Returns null if the target method was not changed.
     */
    static byte[] transformClass(byte[] in, String mName, String mDesc, MethodXform xform, final ClassLoader cl) {
        ClassReader cr = new ClassReader(in);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        final MethodNode target = find(cn, mName, mDesc);
        if (target == null || !xform.apply(target)) return null;

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String t1, String t2) {
                try {
                    Class<?> c1 = Class.forName(t1.replace('/', '.'), false, cl);
                    Class<?> c2 = Class.forName(t2.replace('/', '.'), false, cl);
                    if (c1.isAssignableFrom(c2)) return t1;
                    if (c2.isAssignableFrom(c1)) return t2;
                    if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                    do { c1 = c1.getSuperclass(); } while (!c1.isAssignableFrom(c2));
                    return c1.getName().replace('.', '/');
                } catch (Throwable e) {
                    return "java/lang/Object";
                }
            }
        };
        // For the target method, emit the edited node directly (frames recomputed
        // only for it) and return null so the reader skips it. Every other method
        // passes through to the writer, triggering ASM's verbatim copy path.
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
                if (n.equals(mName) && (mDesc == null || d.equals(mDesc))) {
                    target.accept(cw);
                    return null;
                }
                return super.visitMethod(a, n, d, s, ex);
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    static MethodNode find(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
        }
        return null;
    }
}

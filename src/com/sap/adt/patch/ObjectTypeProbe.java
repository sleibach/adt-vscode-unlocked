package com.sap.adt.patch;

import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import com.sap.adt.tools.core.objecttype.IAdtObjectTypeInfo;
import com.sap.adt.ls.internal.objecttypeinfo.AdtLsObjectTypeUtil;
import com.sap.adt.blues.core.utils.BlueObjectTypeUtil;

/**
 * Object-type unlock helper. The bytecode patch redirects
 * {@code AdtLsObjectTypeUtil.isObjectTypeSupported(info, project)} here.
 *
 * Stock behaviour gates on a hard-coded allowlist
 * ({@code AdtLsObjectTypeInfo.adtLsSupportedObjectTypes}, only ~23 RAP/core
 * types) AND on the type having a resolvable ADT resource. Classic objects such
 * as PROG/P (programs) satisfy the resource check but are missing from the
 * allowlist, so they fall back to the read-only {@code <name>.<type>.jsonc}
 * representation.
 *
 * This helper drops the allowlist requirement: any object type that has a
 * resolvable {@code resourceInfo}/{@code restResourceInfo} (and, for "Blue"
 * types, an available backend resource) is treated as supported. Types with no
 * resource still degrade to the {@code .jsonc} fallback, so nothing regresses.
 *
 * In addition, {@link #ensureSourceMappings()} registers AFF source-file
 * mappings for classic source objects (programs) into
 * {@code AffSfsMapper.objectTypeToFileMappings}, so they open as an editable
 * {@code .abap} source file instead of a server-driven {@code .json}. The
 * mapping mirrors the format SAP uses for the built-in types (e.g. INTF/OI ->
 * {@code <name>.intf.abap} <-> {@code .adt/classlib/interfaces/<name>/<name>.aint}).
 *
 * Every decision is appended to {@code ~/adt-unlock-objtype-probe.log} for
 * verification.
 */
public final class ObjectTypeProbe {

  private static volatile boolean mappingsInstalled = false;

  public static boolean isSupported(AdtLsObjectTypeUtil self, IAdtObjectTypeInfo info, IProject project) {
    ensureSourceMappings();

    String wbType = null;
    Boolean inAllowlist = null;
    Boolean hasResourceInfo = null;
    Boolean hasRestResourceInfo = null;
    Boolean blueAvailable = null;
    boolean result = false;
    String error = null;
    try {
      if (info != null) {
        wbType = info.getGlobalWorkbenchType();
        String key = wbType.substring(0, 4);
        inAllowlist = self.getSupportedObjectTypes().contains(key);

        IAdtObjectTypeInfo resolved = self.getObjectTypeInfo(key);
        if (resolved != null) {
          hasResourceInfo = resolved.getResourceInfo() != null;
          hasRestResourceInfo = resolved.getRestResourceInfo() != null;
          if (hasRestResourceInfo) {
            blueAvailable = BlueObjectTypeUtil.isResourceAvailable(
                resolved, project == null ? null : project.getName());
          }
        }

        // Original logic, MINUS the allowlist requirement: a type is supported
        // when it has a usable ADT resource (Blue types must also be available
        // on the connected backend).
        result = resolved != null
            && (Boolean.TRUE.equals(hasResourceInfo) || Boolean.TRUE.equals(hasRestResourceInfo))
            && (!Boolean.TRUE.equals(hasRestResourceInfo) || Boolean.TRUE.equals(blueAvailable));
      }
    } catch (Throwable t) {
      error = t.getClass().getSimpleName() + ": " + t.getMessage();
      result = false;
    }
    log(wbType, inAllowlist, hasResourceInfo, hasRestResourceInfo, blueAvailable, result, error);
    return result;
  }

  /**
   * Add AFF source-file mappings for classic source objects so the repo tree
   * exposes an editable {@code .abap} file. Idempotent and best-effort: a
   * failure here only means programs keep the server-driven {@code .json}
   * representation, never a crash.
   */
  private static synchronized void ensureSourceMappings() {
    if (mappingsInstalled) return;
    mappingsInstalled = true; // set first: never retry-storm, even on failure
    try {
      Class<?> mapperCls = Class.forName("com.sap.adt.ls.internal.aff.AffSfsMapper");
      Field field = mapperCls.getDeclaredField("objectTypeToFileMappings");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) field.get(null);

      Class<?> mappingCls = Class.forName("com.sap.adt.ls.internal.aff.AffSfsMapper$FileMapper$Mapping");
      Constructor<?> ctor = mappingCls.getDeclaredConstructor(String.class, String.class);
      ctor.setAccessible(true);

      // PROG/P: reports & executable programs. relativePlatformResourcePath
      // "programs/programs"; source ext .asprog, properties ext .approg.
      addMapping(map, ctor, "PROG/P",
          "<object_name>.prog.abap", ".adt/programs/programs/<object_name>/<object_name>.asprog",
          "<object_name>.prog.json", ".adt/programs/programs/<object_name>/<object_name>.approg");

      logLine("ensureSourceMappings: registered " + map.keySet());
    } catch (Throwable t) {
      logLine("ensureSourceMappings FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  private static void addMapping(Map<String, Object> map, Constructor<?> ctor, String wbType,
      String srcAff, String srcSfs, String jsonAff, String jsonSfs) throws Exception {
    if (map.containsKey(wbType)) return; // a future SAP build may add it
    List<Object> mappings = new ArrayList<>();
    mappings.add(ctor.newInstance(srcAff, srcSfs));
    mappings.add(ctor.newInstance(jsonAff, jsonSfs));
    map.put(wbType, mappings);
  }

  private static synchronized void log(String wbType, Boolean inAllowlist, Boolean hasResourceInfo,
      Boolean hasRestResourceInfo, Boolean blueAvailable, boolean result, String error) {
    logLine(wbType + " | allowlist=" + inAllowlist + " | resourceInfo=" + hasResourceInfo
        + " | restResourceInfo=" + hasRestResourceInfo + " | blueAvailable=" + blueAvailable
        + " | result=" + result + (error == null ? "" : " | error=" + error));
  }

  private static void logLine(String msg) {
    try (FileWriter w = new FileWriter(
        Paths.get(System.getProperty("user.home"), "adt-unlock-objtype-probe.log").toFile(), true)) {
      w.write(LocalDateTime.now() + " | " + msg + "\n");
    } catch (Exception ignore) {
      // never let logging break the language server
    }
  }
}

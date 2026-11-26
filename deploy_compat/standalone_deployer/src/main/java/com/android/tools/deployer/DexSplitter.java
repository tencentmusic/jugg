package com.android.tools.deployer;

import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.ApkEntry;
import com.android.tools.deployer.model.DexClass;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public interface DexSplitter {
   Collection<DexClass> split(ApkEntry dex, Predicate<DexClass> keepCode) throws DeployerException;

   default boolean cache(List<Apk> apks) throws DeployerException {
      for(Apk apk : apks) {
         for(ApkEntry file : apk.apkEntries.values()) {
            if (file.getName().endsWith(".dex")) {
               this.split(file, (Predicate)null);
            }
         }
      }

      return true;
   }
}

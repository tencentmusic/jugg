package com.android.tools.deployer;

import com.android.annotations.Trace;
import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.model.DexClass;
import com.android.tools.deployer.model.FileDiff;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class DexComparator {
   @Trace
   public ChangedClasses compare(List<FileDiff> dexDiffs, DexSplitter splitter) throws DeployerException {
      Map<String, Long> oldChecksums = new HashMap();

      for(FileDiff diff : dexDiffs) {
         if (diff.status != FileDiff.Status.CREATED) {
            for(DexClass clz : splitter.split(diff.oldFile, null)) {
               oldChecksums.putIfAbsent(clz.name, clz.checksum);
            }
         }
      }

      List<DexClass> newClasses = new ArrayList();
      List<DexClass> modifiedClasses = new ArrayList();

      for(FileDiff diff : dexDiffs) {
         Predicate<DexClass> keepCode = (clzx) -> {
            Long oldChecksum = (Long)oldChecksums.get(clzx.name);
            return oldChecksum == null || clzx.checksum != oldChecksum;
         };

         for(DexClass klass : splitter.split(diff.newFile, keepCode)) {
            if (klass.code == null) {
               oldChecksums.put(klass.name, null);
            } else if (oldChecksums.containsKey(klass.name)) {
               if (oldChecksums.get(klass.name) != null) {
                  modifiedClasses.add(klass);
               }
            } else {
               newClasses.add(klass);
            }
         }
      }

      return new ChangedClasses(newClasses, modifiedClasses);
   }

   public static class ChangedClasses {
      public final List<DexClass> newClasses;
      public final List<DexClass> modifiedClasses;

      public ChangedClasses(List<DexClass> newClasses, List<DexClass> modifiedClasses) {
         this.newClasses = newClasses;
         this.modifiedClasses = modifiedClasses;
      }
   }
}

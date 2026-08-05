package com.android.tools.deployer;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.ClassDef.FieldReInitState;
import com.android.tools.deploy.proto.Deploy.ClassDef.FieldReInitState.VariableState;
import com.android.tools.deployer.model.ApkEntry;
import com.android.tools.deployer.model.DexClass;
import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DexFilePerClassFileConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.inspector.ClassInspector;
import com.android.tools.r8.inspector.FieldInspector;
import com.android.tools.r8.inspector.Inspector;
import com.android.tools.r8.inspector.ValueInspector;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.references.FieldReference;
import com.android.tools.tracer.Trace;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class D8DexSplitter implements DexSplitter {
   public Collection<DexClass> split(ApkEntry dex, Predicate<DexClass> keepCode) {
      try (Trace ignored = Trace.begin("split " + dex.getName())) {
         D8Command.Builder newBuilder = D8Command.builder();
         DexConsumer consumer = new DexConsumer(dex, keepCode);
         newBuilder.addDexProgramData(this.readDex(dex), Origin.unknown());
         Objects.requireNonNull(consumer);
         newBuilder.setDexClassChecksumFilter(consumer::parseFilter);
         newBuilder.addOutputInspection(consumer);
         newBuilder.setProgramConsumer(consumer);
         D8.run((D8Command)newBuilder.build());
         consumer.join();
         return (Collection)consumer.classes.values().stream().map((dexClass) -> {
            Collection<Deploy.ClassDef.FieldReInitState> states = consumer.variableStates.get(dexClass.name);
            return states == null ? dexClass : new DexClass(dexClass, ImmutableList.copyOf(states));
         }).collect(Collectors.toList());
      } catch (CompilationFailedException | InterruptedException e) {
         throw new RuntimeException(e);
      }
   }

   protected byte[] readDex(ApkEntry dex) {
      try {
         ZipFile file = new ZipFile(dex.getApk().path);

         byte[] var4;
         try {
            ZipEntry entry = file.getEntry(dex.getName());
            var4 = ByteStreams.toByteArray(file.getInputStream(entry));
         } catch (Throwable var6) {
            try {
               file.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }

            throw var6;
         }

         file.close();
         return var4;
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   private static String typeNameToClassName(String typeName) {
      assert typeName.startsWith("L");

      assert typeName.endsWith(";");

      return typeName.substring(1, typeName.length() - 1).replace('/', '.');
   }

   private static class DexConsumer implements DexFilePerClassFileConsumer, Consumer<Inspector> {
      private final Map<String, DexClass> classes = new HashMap();
      private final Multimap<String, Deploy.ClassDef.FieldReInitState> variableStates = ArrayListMultimap.create();
      private final CountDownLatch finished = new CountDownLatch(1);
      private final Predicate<DexClass> keepCode;
      private final ApkEntry dex;

      private DexConsumer(ApkEntry dex, Predicate<DexClass> keepCode) {
         this.dex = dex;
         this.keepCode = keepCode;
      }

      public void finished(DiagnosticsHandler handler) {
         this.finished.countDown();
      }

      public void join() throws InterruptedException {
         this.finished.await();
      }

      public boolean parseFilter(String classDescriptor, Long checksum) {
         DexClass c = new DexClass(D8DexSplitter.typeNameToClassName(classDescriptor), checksum == null ? 0L : checksum, (byte[])null, this.dex);
         synchronized(this) {
            this.classes.put(classDescriptor, c);
         }

         return this.keepCode != null ? this.keepCode.test(c) : false;
      }

      public synchronized void accept(String name, ByteDataView data, Set<String> descriptors, DiagnosticsHandler handler) {
         DexClass clazz = (DexClass)this.classes.get(name);
         String className = D8DexSplitter.typeNameToClassName(name);
         if (clazz == null) {
            CRC32 crc = new CRC32();
            crc.update(data.getBuffer(), data.getOffset(), data.getLength());
            long newChecksum = crc.getValue();
            clazz = new DexClass(className, newChecksum, (byte[])null, this.dex);
            this.classes.put(name, clazz);
         }

         if (this.keepCode != null && this.keepCode.test(clazz)) {
            this.classes.put(name, new DexClass(className, clazz.checksum, data.copyByteData(), this.dex));
         }

      }

      public void accept(Inspector inspector) {
         inspector.forEachClass((classInspector) -> classInspector.forEachField((fieldInspector) -> this.inspectField(classInspector, fieldInspector)));
      }

      private void inspectField(ClassInspector classInspector, FieldInspector fieldInspector) {
         Deploy.ClassDef.FieldReInitState.Builder state = FieldReInitState.newBuilder();
         FieldReference field = fieldInspector.getFieldReference();
         state.setName(field.getFieldName());
         state.setType(field.getFieldType().getDescriptor());
         state.setStaticVar(fieldInspector.isStatic());
         Optional<ValueInspector> value = fieldInspector.getInitialValue();
         if (fieldInspector.isStatic() && fieldInspector.isFinal() && value.isPresent()) {
            state.setState(VariableState.CONSTANT);
            ValueInspector valueInspector = (ValueInspector)value.get();
            if (valueInspector.isByteValue()) {
               state.setValue(Byte.toString(((ValueInspector)value.get()).asByteValue().getByteValue()));
            } else if (valueInspector.isCharValue()) {
               state.setValue(Character.toString(((ValueInspector)value.get()).asCharValue().getCharValue()));
            } else if (valueInspector.isIntValue()) {
               state.setValue(Integer.toString(((ValueInspector)value.get()).asIntValue().getIntValue()));
            } else if (valueInspector.isLongValue()) {
               state.setValue(Long.toString(((ValueInspector)value.get()).asLongValue().getLongValue()));
            } else if (valueInspector.isShortValue()) {
               state.setValue(Short.toString(((ValueInspector)value.get()).asShortValue().getShortValue()));
            } else if (valueInspector.isDoubleValue()) {
               state.setValue(Double.toString(((ValueInspector)value.get()).asDoubleValue().getDoubleValue()));
            } else if (valueInspector.isFloatValue()) {
               state.setValue(Float.toString(((ValueInspector)value.get()).asFloatValue().getFloatValue()));
            } else if (valueInspector.isBooleanValue()) {
               state.setValue(Boolean.toString(((ValueInspector)value.get()).asBooleanValue().getBooleanValue()));
            }
         } else {
            state.setState(VariableState.UNKNOWN);
         }

         synchronized(this) {
            this.variableStates.put(D8DexSplitter.typeNameToClassName(classInspector.getClassReference().getDescriptor()), state.build());
         }
      }
   }
}

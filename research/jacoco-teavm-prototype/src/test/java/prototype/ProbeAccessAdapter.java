/*
 * Copyright 2026 Instanto contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package prototype;

import org.objectweb.asm.*;

/** Lower only JaCoCo 0.8.15's generated constant-dynamic lookup, preserving its probes. */
final class ProbeAccessAdapter {
  private static final String BOOTSTRAP =
      "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)[Z";

  static byte[] lower(byte[] bytes) {
    ClassReader reader = new ClassReader(bytes);
    ClassWriter writer = new ClassWriter(0);
    String owner = reader.getClassName();
    reader.accept(
        new ClassVisitor(Opcodes.ASM9, writer) {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String desc, String signature, String[] exceptions) {
            if (name.equals("$jacocoInit") && desc.equals(BOOTSTRAP)) {
              if ((access & Opcodes.ACC_SYNTHETIC) == 0)
                throw new IllegalArgumentException("Non-synthetic JaCoCo bootstrap");
              desc = "()[Z";
            }
            return new MethodVisitor(
                Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
              @Override
              public void visitLdcInsn(Object value) {
                if (value instanceof ConstantDynamic c && c.getName().equals("$jacocoData")) {
                  Handle bootstrap = c.getBootstrapMethod();
                  if (!c.getDescriptor().equals("Ljava/lang/Object;")
                      || c.getBootstrapMethodArgumentCount() != 0
                      || !bootstrap.getOwner().equals(owner)
                      || !bootstrap.getName().equals("$jacocoInit")
                      || !bootstrap.getDesc().equals(BOOTSTRAP)
                      || bootstrap.getTag() != Opcodes.H_INVOKESTATIC) {
                    throw new IllegalArgumentException(
                        "Unsupported JaCoCo constant-dynamic layout");
                  }
                  super.visitMethodInsn(
                      Opcodes.INVOKESTATIC, owner, "$jacocoInit", "()[Z", bootstrap.isInterface());
                } else {
                  super.visitLdcInsn(value);
                }
              }
            };
          }
        },
        0);
    return writer.toByteArray();
  }
}

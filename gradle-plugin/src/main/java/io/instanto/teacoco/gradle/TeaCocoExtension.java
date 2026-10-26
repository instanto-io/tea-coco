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
package io.instanto.teacoco.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Settings for a single browser Test task and its matching report. */
public abstract class TeaCocoExtension {
  public abstract Property<Boolean> getEnabled();

  public abstract Property<String> getVersion();

  /** lines (default) or jacoco for original-bytecode line/branch coverage. */
  public abstract Property<String> getMode();

  public abstract ConfigurableFileCollection getClassRoots();

  public abstract ConfigurableFileCollection getExecutionData();

  public abstract Property<String> getTestTask();

  public abstract ConfigurableFileCollection getSourceRoots();

  public abstract DirectoryProperty getDataDirectory();

  public abstract RegularFileProperty getReportFile();
}

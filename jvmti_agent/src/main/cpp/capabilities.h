/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#ifndef CAPABILITIES_H
#define CAPABILITIES_H

#include "jvmti.h"

namespace deploy {

const jvmtiCapabilities REQUIRED_CAPABILITIES = {
    .can_tag_objects = 0,
    .can_generate_field_modification_events = 0,
    .can_generate_field_access_events = 0,
    .can_get_bytecodes = 0,
    .can_get_synthetic_attribute = 0,
    .can_get_owned_monitor_info = 0,
    .can_get_current_contended_monitor = 0,
    .can_get_monitor_info = 0,
    .can_pop_frame = 0,
    .can_redefine_classes = 1,
    .can_signal_thread = 0,
    .can_get_source_file_name = 0,
    .can_get_line_numbers = 0,
    .can_get_source_debug_extension = 0,
    .can_access_local_variables = 0,
    .can_maintain_original_method_order = 0,
    .can_generate_single_step_events = 0,
    .can_generate_exception_events = 0,
    .can_generate_frame_pop_events = 0,
    .can_generate_breakpoint_events = 0,
    .can_suspend = 0,
    .can_redefine_any_class = 0,
    .can_get_current_thread_cpu_time = 0,
    .can_get_thread_cpu_time = 0,
    .can_generate_method_entry_events = 0,
    .can_generate_method_exit_events = 0,
    .can_generate_all_class_hook_events = 0,
    .can_generate_compiled_method_load_events = 0,
    .can_generate_monitor_events = 0,
    .can_generate_vm_object_alloc_events = 0,
    .can_generate_native_method_bind_events = 0,
    .can_generate_garbage_collection_events = 0,
    .can_generate_object_free_events = 0,
    .can_force_early_return = 0,
    .can_get_owned_monitor_stack_depth_info = 0,
    .can_get_constant_pool = 0,
    .can_set_native_method_prefix = 0,
    .can_retransform_classes = 1,
    .can_retransform_any_class = 0,
    .can_generate_resource_exhaustion_heap_events = 0,
    .can_generate_resource_exhaustion_threads_events = 0,
};

}  // namespace deploy

#endif

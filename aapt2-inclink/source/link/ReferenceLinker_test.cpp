/*
 * Copyright (C) 2015 The Android Open Source Project
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
 */

#include "link/ReferenceLinker.h"
#include "link/TableMerger.h"
#include "compile/IdAssigner.h"

#include "test/Test.h"

using ::android::ResTable_map;
using ::testing::Eq;
using ::testing::IsNull;
using ::testing::NotNull;

namespace aapt {

// A retained binary table may contain references unavailable to the current SDK.
TEST(ReferenceLinkerTest, PreserveUntouchedBaselineReferences) {
  auto style = util::make_unique<Style>();
  style->parent = Reference(ResourceId(0x01830480));
  auto table = test::ResourceTableBuilder()
      .SetPackageId("com.app.test", 0x7f)
      .AddValue("com.app.test:style/Old", std::move(style))
      .AddValue("com.app.test:string/alias",
                util::make_unique<Reference>(ResourceId(0x01049999)))
      .Build();
  auto context = test::ContextBuilder().SetCompilationPackage("com.app.test")
      .SetPackageId(0x7f).Build();
  const std::set<const ResourceConfigValue*> inputs;
  ReferenceLinker linker(&inputs);
  ASSERT_TRUE(linker.Consume(context.get(), table.get()));
  EXPECT_EQ(ResourceId(0x01830480),
            test::GetValue<Style>(table.get(), "com.app.test:style/Old")->parent.value().id.value());
  EXPECT_EQ(ResourceId(0x01049999),
            test::GetValue<Reference>(table.get(), "com.app.test:string/alias")->id.value());
}

TEST(ReferenceLinkerTest, LinkSimpleReferences) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.test", 0x7f)
          .AddReference("com.app.test:string/foo", ResourceId(0x7f020000),
                        "com.app.test:string/bar")

          // Test use of local reference (w/o package name).
          .AddReference("com.app.test:string/bar", ResourceId(0x7f020001),
                        "string/baz")

          .AddReference("com.app.test:string/baz", ResourceId(0x7f020002),
                        "android:string/ok")
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddPublicSymbol("android:string/ok", ResourceId(0x01040034))
                  .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context.get(), table.get()));

  Reference* ref = test::GetValue<Reference>(table.get(), "com.app.test:string/foo");
  ASSERT_THAT(ref, NotNull());
  ASSERT_TRUE(ref->id);
  EXPECT_EQ(ResourceId(0x7f020001), ref->id.value());

  ref = test::GetValue<Reference>(table.get(), "com.app.test:string/bar");
  ASSERT_THAT(ref, NotNull());
  ASSERT_TRUE(ref->id);
  EXPECT_EQ(ResourceId(0x7f020002), ref->id.value());

  ref = test::GetValue<Reference>(table.get(), "com.app.test:string/baz");
  ASSERT_THAT(ref, NotNull());
  ASSERT_TRUE(ref->id);
  EXPECT_EQ(ResourceId(0x01040034), ref->id.value());
}

TEST(ReferenceLinkerTest, LinkStyleAttributes) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.test", 0x7f)
          .AddValue("com.app.test:style/Theme",
                    test::StyleBuilder()
                        .SetParent("android:style/Theme.Material")
                        .AddItem("android:attr/foo",
                                 ResourceUtils::TryParseColor("#ff00ff"))
                        .AddItem("android:attr/bar", {} /* placeholder */)
                        .Build())
          .Build();

  {
    // We need to fill in the value for the attribute android:attr/bar after we
    // build the table, because we need access to the string pool.
    Style* style = test::GetValue<Style>(table.get(), "com.app.test:style/Theme");
    ASSERT_THAT(style, NotNull());
    style->entries.back().value =
        util::make_unique<RawString>(table->string_pool.MakeRef("one|two"));
  }

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddPublicSymbol("android:style/Theme.Material",
                                   ResourceId(0x01060000))
                  .AddPublicSymbol("android:attr/foo", ResourceId(0x01010001),
                                   test::AttributeBuilder()
                                       .SetTypeMask(ResTable_map::TYPE_COLOR)
                                       .Build())
                  .AddPublicSymbol("android:attr/bar", ResourceId(0x01010002),
                                   test::AttributeBuilder()
                                       .SetTypeMask(ResTable_map::TYPE_FLAGS)
                                       .AddItem("one", 0x01)
                                       .AddItem("two", 0x02)
                                       .Build())
                  .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context.get(), table.get()));

  Style* style = test::GetValue<Style>(table.get(), "com.app.test:style/Theme");
  ASSERT_THAT(style, NotNull());
  ASSERT_TRUE(style->parent);
  ASSERT_TRUE(style->parent.value().id);
  EXPECT_EQ(ResourceId(0x01060000), style->parent.value().id.value());

  ASSERT_EQ(2u, style->entries.size());

  ASSERT_TRUE(style->entries[0].key.id);
  EXPECT_EQ(ResourceId(0x01010001), style->entries[0].key.id.value());
  ASSERT_THAT(ValueCast<BinaryPrimitive>(style->entries[0].value.get()), NotNull());

  ASSERT_TRUE(style->entries[1].key.id);
  EXPECT_EQ(ResourceId(0x01010002), style->entries[1].key.id.value());
  ASSERT_THAT(ValueCast<BinaryPrimitive>(style->entries[1].value.get()), NotNull());
}

TEST(ReferenceLinkerTest, LinkMangledReferencesAndAttributes) {
  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(
              NameManglerPolicy{"com.app.test", {"com.android.support"}})
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddPublicSymbol("com.app.test:attr/com.android.support$foo",
                                   ResourceId(0x7f010000),
                                   test::AttributeBuilder()
                                       .SetTypeMask(ResTable_map::TYPE_COLOR)
                                       .Build())
                  .Build())
          .Build();

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.test", 0x7f)
          .AddValue("com.app.test:style/Theme", ResourceId(0x7f020000),
                    test::StyleBuilder()
                        .AddItem("com.android.support:attr/foo",
                                 ResourceUtils::TryParseColor("#ff0000"))
                        .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context.get(), table.get()));

  Style* style = test::GetValue<Style>(table.get(), "com.app.test:style/Theme");
  ASSERT_THAT(style, NotNull());
  ASSERT_EQ(1u, style->entries.size());
  ASSERT_TRUE(style->entries.front().key.id);
  EXPECT_EQ(ResourceId(0x7f010000), style->entries.front().key.id.value());
}

TEST(ReferenceLinkerTest, FailToLinkPrivateSymbols) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.test", 0x7f)
          .AddReference("com.app.test:string/foo", ResourceId(0x7f020000),
                        "android:string/hidden")
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddSymbol("android:string/hidden", ResourceId(0x01040034))
                  .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_FALSE(linker.Consume(context.get(), table.get()));
}

TEST(ReferenceLinkerTest, FailToLinkPrivateMangledSymbols) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.test", 0x7f)
          .AddReference("com.app.test:string/foo", ResourceId(0x7f020000),
                        "com.app.lib:string/hidden")
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(
              NameManglerPolicy{"com.app.test", {"com.app.lib"}})
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddSymbol("com.app.test:string/com.app.lib$hidden",
                             ResourceId(0x7f040034))
                  .Build())

          .Build();

  ReferenceLinker linker;
  ASSERT_FALSE(linker.Consume(context.get(), table.get()));
}

TEST(ReferenceLinkerTest, FailToLinkPrivateStyleAttributes) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.test", 0x7f)
          .AddValue("com.app.test:style/Theme",
                    test::StyleBuilder()
                        .AddItem("android:attr/hidden",
                                 ResourceUtils::TryParseColor("#ff00ff"))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddSymbol("android:attr/hidden", ResourceId(0x01010001),
                             test::AttributeBuilder()
                                 .SetTypeMask(android::ResTable_map::TYPE_COLOR)
                                 .Build())
                  .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_FALSE(linker.Consume(context.get(), table.get()));
}

TEST(ReferenceLinkerTest, AppsWithSamePackageButDifferentIdAreVisibleNonPublic) {
  NameMangler mangler(NameManglerPolicy{"com.app.test"});
  SymbolTable table(&mangler);
  table.AppendSource(test::StaticSymbolSourceBuilder()
                         .AddSymbol("com.app.test:string/foo", ResourceId(0x7f010000))
                         .Build());

  std::string error;
  const CallSite call_site{"com.app.test"};
  std::unique_ptr<IAaptContext> context =
    test::ContextBuilder()
        .SetCompilationPackage("com.app.test")
        .SetPackageId(0x7f)
        .Build();
  const SymbolTable::Symbol* symbol = ReferenceLinker::ResolveSymbolCheckVisibility(
      *test::BuildReference("com.app.test:string/foo"), call_site, context.get(), &table, &error);
  ASSERT_THAT(symbol, NotNull());
  EXPECT_TRUE(error.empty());
}

TEST(ReferenceLinkerTest, AppsWithDifferentPackageCanNotUseEachOthersAttribute) {
  NameMangler mangler(NameManglerPolicy{"com.app.ext"});
  SymbolTable table(&mangler);
  table.AppendSource(test::StaticSymbolSourceBuilder()
                         .AddSymbol("com.app.test:attr/foo", ResourceId(0x7f010000),
                                    test::AttributeBuilder().Build())
                         .AddPublicSymbol("com.app.test:attr/public_foo", ResourceId(0x7f010001),
                                          test::AttributeBuilder().Build())
                         .Build());
  std::unique_ptr<IAaptContext> context =
    test::ContextBuilder()
        .SetCompilationPackage("com.app.ext")
        .SetPackageId(0x7f)
        .Build();

  std::string error;
  const CallSite call_site{"com.app.ext"};

  EXPECT_FALSE(ReferenceLinker::CompileXmlAttribute(
      *test::BuildReference("com.app.test:attr/foo"), call_site, context.get(), &table, &error));
  EXPECT_FALSE(error.empty());

  error = "";
  ASSERT_TRUE(ReferenceLinker::CompileXmlAttribute(
      *test::BuildReference("com.app.test:attr/public_foo"), call_site, context.get(), &table,
      &error));
  EXPECT_TRUE(error.empty());
}

TEST(ReferenceLinkerTest, ReferenceWithNoPackageUsesCallSitePackage) {
  NameMangler mangler(NameManglerPolicy{"com.app.test"});
  SymbolTable table(&mangler);
  table.AppendSource(test::StaticSymbolSourceBuilder()
                         .AddSymbol("com.app.test:string/foo", ResourceId(0x7f010000))
                         .AddSymbol("com.app.lib:string/foo", ResourceId(0x7f010001))
                         .Build());
  std::unique_ptr<IAaptContext> context =
    test::ContextBuilder()
        .SetCompilationPackage("com.app.test")
        .SetPackageId(0x7f)
        .Build();

  const SymbolTable::Symbol* s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/foo"),
                                                                CallSite{"com.app.test"},
                                                                context.get(), &table);
  ASSERT_THAT(s, NotNull());
  EXPECT_THAT(s->id, Eq(make_value<ResourceId>(0x7f010000)));

  s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/foo"), CallSite{"com.app.lib"},
                                     context.get(), &table);
  ASSERT_THAT(s, NotNull());
  EXPECT_THAT(s->id, Eq(make_value<ResourceId>(0x7f010001)));

  EXPECT_THAT(ReferenceLinker::ResolveSymbol(*test::BuildReference("string/foo"),
                                             CallSite{"com.app.bad"}, context.get(), &table),
              IsNull());
}

TEST(ReferenceLinkerTest, ReferenceSymbolFromOtherSplit) {
  NameMangler mangler(NameManglerPolicy{"com.app.test"});
  SymbolTable table(&mangler);
  table.AppendSource(test::StaticSymbolSourceBuilder()
                         .AddSymbol("com.app.test.feature:string/bar", ResourceId(0x80010000))
                         .Build());
  std::set<std::string> split_name_dependencies;
  split_name_dependencies.insert("feature");
  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x81)
          .SetSplitNameDependencies(split_name_dependencies)
          .Build();

  const SymbolTable::Symbol* s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/bar"),
                                                                CallSite{"com.app.test"},
                                                                context.get(), &table);
  ASSERT_THAT(s, NotNull());
  EXPECT_THAT(s->id, Eq(make_value<ResourceId>(0x80010000)));

  s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/foo"), CallSite{"com.app.lib"},
                                     context.get(), &table);
  EXPECT_THAT(s, IsNull());

  context =
    test::ContextBuilder()
        .SetCompilationPackage("com.app.test")
        .SetPackageId(0x81)
        .Build();
  s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/bar"),CallSite{"com.app.test"},
                                     context.get(), &table);

  EXPECT_THAT(s, IsNull());
}

class IncrementalReferenceLinkerTest : public ::testing::Test {
 protected:
  void SetUp() override {
    context = test::ContextBuilder().SetCompilationPackage("com.app.test")
        .SetPackageId(0x7f).Build();
    context->GetExternalSymbols()->PrependSource(
        util::make_unique<ResourceTableSymbolSource>(&table));
  }

  bool Add(const char* name, std::unique_ptr<Value> value,
           const char* config = "", const char* product = "") {
    return table.AddResource(test::ParseNameOrDie(name), test::ParseConfigOrDie(config),
                             product, std::move(value), context->GetDiagnostics());
  }

  bool Link(ResourceTable* input) {
    TableMergerOptions options;
    options.auto_add_overlay = true;
    options.track_merged_values = true;
    TableMerger merger(context.get(), &table, options);
    if (!merger.Merge({}, input, true)) return false;
    IdAssigner assigner;
    if (!assigner.Consume(context.get(), &table)) return false;
    ReferenceLinker linker(&merger.merged_values());
    return linker.Consume(context.get(), &table);
  }

  std::unique_ptr<Reference> BadReference() {
    return util::make_unique<Reference>(ResourceId(0x01830480));
  }

  ResourceTable table;
  std::unique_ptr<IAaptContext> context;
};

TEST_F(IncrementalReferenceLinkerTest, CurrentMissingTargetStillFails) {
  auto input = test::ResourceTableBuilder()
      .AddValue("com.app.test:string/new", BadReference()).Build();
  EXPECT_FALSE(Link(input.get()));
}

TEST_F(IncrementalReferenceLinkerTest, CurrentReferencesToBaselineAreResolved) {
  ASSERT_TRUE(Add("com.app.test:string/old", util::make_unique<Id>()));
  auto input = test::ResourceTableBuilder()
      .AddReference("com.app.test:string/new", "string/old").Build();
  ASSERT_TRUE(Link(input.get()));
  auto ref = test::GetValue<Reference>(&table, "com.app.test:string/new");
  ASSERT_TRUE(ref->id);
  EXPECT_EQ(table.FindResource(test::ParseNameOrDie("com.app.test:string/old"))
                .value().entry->id.value(), ref->id.value().entry_id());
}

TEST_F(IncrementalReferenceLinkerTest, StyleInPlaceMergeIsCurrentEvenWithSameParent) {
  auto old_style = util::make_unique<Style>();
  old_style->parent = *BadReference();
  ASSERT_TRUE(Add("com.app.test:style/Old", std::move(old_style)));
  auto new_style = util::make_unique<Style>();
  new_style->parent = *BadReference();
  auto input = test::ResourceTableBuilder()
      .AddValue("com.app.test:style/Old", std::move(new_style)).Build();
  EXPECT_FALSE(Link(input.get()));
}

TEST_F(IncrementalReferenceLinkerTest, StyleItemOnlyMergeStillChecksInheritedParent) {
  auto old_style = util::make_unique<Style>();
  old_style->parent = *BadReference();
  ASSERT_TRUE(Add("com.app.test:style/Old", std::move(old_style)));
  auto input = test::ResourceTableBuilder()
      .AddValue("com.app.test:style/Old", util::make_unique<Style>()).Build();
  EXPECT_FALSE(Link(input.get()));
}

TEST_F(IncrementalReferenceLinkerTest, SeparateConfigurationsAndProductsRemainUntouched) {
  ASSERT_TRUE(Add("com.app.test:string/value", BadReference(), "fr"));
  ASSERT_TRUE(Add("com.app.test:string/value", BadReference(), "", "tablet"));
  auto input = test::ResourceTableBuilder()
      .AddString("com.app.test:string/value", "updated").Build();
  ASSERT_TRUE(Link(input.get()));
  EXPECT_EQ(ResourceId(0x01830480), test::GetValueForConfig<Reference>(
      &table, "com.app.test:string/value", test::ParseConfigOrDie("fr"))->id.value());
  EXPECT_EQ(ResourceId(0x01830480), test::GetValueForConfigAndProduct<Reference>(
      &table, "com.app.test:string/value", {}, "tablet")->id.value());
}

TEST_F(IncrementalReferenceLinkerTest, CurrentConfigurationWithBadReferenceFails) {
  ASSERT_TRUE(Add("com.app.test:string/value", BadReference(), "fr"));
  auto input = test::ResourceTableBuilder()
      .AddValue("com.app.test:string/value", test::ParseConfigOrDie("fr"), {},
                BadReference()).Build();
  EXPECT_FALSE(Link(input.get()));
}

TEST_F(IncrementalReferenceLinkerTest, SelectionIsRebuiltForEveryInvocation) {
  ASSERT_TRUE(Add("com.app.test:string/old", BadReference()));
  auto first = test::ResourceTableBuilder().AddString("com.app.test:string/new", "one").Build();
  ASSERT_TRUE(Link(first.get()));
  auto second = test::ResourceTableBuilder().AddValue("com.app.test:string/old", BadReference()).Build();
  EXPECT_FALSE(Link(second.get()));
}

TEST_F(IncrementalReferenceLinkerTest, UntouchedArraysAndStyleItemsArePreserved) {
  auto array = util::make_unique<Array>();
  array->elements.push_back(BadReference());
  ASSERT_TRUE(Add("com.app.test:array/old", std::move(array)));
  auto style = test::StyleBuilder().AddItem("android:attr/missing", BadReference()).Build();
  ASSERT_TRUE(Add("com.app.test:style/Old", std::move(style)));
  auto input = test::ResourceTableBuilder().AddString("com.app.test:string/new", "ok").Build();
  ASSERT_TRUE(Link(input.get()));
  EXPECT_EQ(ResourceId(0x01830480), ValueCast<Reference>(
      test::GetValue<Array>(&table, "com.app.test:array/old")->elements[0].get())->id.value());
}

TEST_F(IncrementalReferenceLinkerTest, ReconstructedStyleablesRemainStrict) {
  ASSERT_TRUE(Add("com.app.test:styleable/Old",
                 test::StyleableBuilder().AddItem("android:attr/missing").Build()));
  auto input = test::ResourceTableBuilder().AddString("com.app.test:string/new", "ok").Build();
  EXPECT_FALSE(Link(input.get()));
}

TEST_F(IncrementalReferenceLinkerTest, OrdinaryLinkStillChecksEntireBaseline) {
  ASSERT_TRUE(Add("com.app.test:string/old", BadReference()));
  ReferenceLinker linker;
  EXPECT_FALSE(linker.Consume(context.get(), &table));
}

TEST_F(IncrementalReferenceLinkerTest, RepairingBadParentUsesNewParent) {
  auto old_style = util::make_unique<Style>();
  old_style->parent = *BadReference();
  ASSERT_TRUE(Add("com.app.test:style/Old", std::move(old_style)));
  ASSERT_TRUE(Add("com.app.test:style/Valid", util::make_unique<Style>()));
  auto input = test::ResourceTableBuilder()
      .AddValue("com.app.test:style/Old",
                test::StyleBuilder().SetParent("style/Valid").Build()).Build();
  ASSERT_TRUE(Link(input.get()));
  auto parent = test::GetValue<Style>(&table, "com.app.test:style/Old")->parent.value();
  ASSERT_TRUE(parent.id);
  EXPECT_NE(ResourceId(0x01830480), parent.id.value());
}

TEST_F(IncrementalReferenceLinkerTest, CurrentArrayReferencesAreChecked) {
  auto array = util::make_unique<Array>();
  array->elements.push_back(BadReference());
  auto input = test::ResourceTableBuilder()
      .AddValue("com.app.test:array/new", std::move(array)).Build();
  EXPECT_FALSE(Link(input.get()));
}

TEST_F(IncrementalReferenceLinkerTest, CurrentProductWithBadReferenceFails) {
  ASSERT_TRUE(Add("com.app.test:string/value", util::make_unique<Id>()));
  ResourceTable input;
  ASSERT_TRUE(input.AddResource(test::ParseNameOrDie("com.app.test:string/value"), {},
                                "tablet", BadReference(), context->GetDiagnostics()));
  EXPECT_FALSE(Link(&input));
}

TEST_F(IncrementalReferenceLinkerTest, InputSelectionCannotSelectSameNamedValueInAnotherTable) {
  ASSERT_TRUE(Add("com.app.test:string/value", BadReference()));
  ResourceTable other;
  ASSERT_TRUE(other.AddResource(test::ParseNameOrDie("com.app.test:string/value"), {}, "",
                                util::make_unique<Id>(), context->GetDiagnostics()));
  auto other_value = other.FindResource(test::ParseNameOrDie("com.app.test:string/value"))
      .value().entry->FindValue({}, "");
  const std::set<const ResourceConfigValue*> other_inputs{other_value};
  ReferenceLinker linker(&other_inputs);
  EXPECT_TRUE(linker.Consume(context.get(), &table));
  EXPECT_EQ(ResourceId(0x01830480),
            test::GetValue<Reference>(&table, "com.app.test:string/value")->id.value());
}

}  // namespace aapt

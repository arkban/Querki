package querki.tags

import models.Thing
import querki.test._
import querki.types.SimplePropertyBundle

class TagTests extends QuerkiTests {
  lazy val Tags = interface[querki.tags.Tags]

  "Tags" should {
    // Test for .3y28auo
    "be able to use the Name Property" in {
      class TSpace extends CommonSpace {
        val tagThing = new SimpleTestThing("Tag Thing", setTagsProp("Tag With a Name"))
      }
      implicit val s = new TSpace

      pql("""[[Tag Thing -> My Set of Tags -> Name]]""") should
        equal("Tag With a Name")
    }

    // Test for .3y28b1a
    "be comparable to Text" in {
      class TSpace extends CommonSpace {
        val thing1 = new SimpleTestThing("Thing 1", singleTagProp("My Tag"))
      }
      implicit val s = new TSpace

      // This worked from the beginning:
      pql("""[[_equals(Thing 1 -> Single Tag -> ""[[Name]]"", ""My Tag"")]]""") should equal("true")
      // This requires coercion from Plain Text to Parsed Text:
      pql("""[[_equals(Thing 1 -> Single Tag -> Name, ""My Tag"")]]""") should equal("true")
      // This requires coercion from Tag Type to Parsed Text:
      pql("""[[_equals(Thing 1 -> Single Tag, ""My Tag"")]]""") should equal("true")
    }

    // Part of QI.9v5kemx -- we are intentially allowing dereferencing of Tags in Properties, but not in
    // explicit QL:
    "be able to dereference a Property" in {
      class TSpace extends CommonSpace {
        val thing1 = new SimpleTestThing("Thing 1", singleTagProp("My Tag"))
      }
      implicit val s = new TSpace

      pql("""[[Thing 1 -> Single Tag -> Single Text]]""") should equal("")
    }
  }

  "Tags in QL" should {
    // QI.9v5kemx:
    "give an error if you try to apply a Property" in {
      implicit val s = commonSpace

      pql("""[[Unknown Name -> Single Text]]""") should equal(expectedWarning("Tags.noProp"))
    }
  }

  "TagThing" should {
    // Test for QI.9v5kemu
    "resolve correctly whether given dashes or spaces" in {
      val Tags = interface[querki.tags.Tags]
      val Links = interface[querki.links.Links]

      class TSpace extends CommonSpace {
        val linkModel = new SimpleTestThing("Linked Model")
        val linkedTagsProp = new TestProperty(TagType, QSet, "Tags With Prop", Links.LinkModelProp(linkModel))

        val model = new SimpleTestThing("My Model")
        val thing1 = new TestThing("Thing 1", model, linkedTagsProp("My Tag"))
      }
      implicit val s = new TSpace
      val state = s.state

      val tagWithSpaces: Thing = Tags.getTag("My Tag", state)
      val tagWithDashes: Thing = Tags.getTag("My-Tag", state)

      tagWithSpaces.model should equal(s.linkModel.id)
      tagWithDashes.model should equal(s.linkModel.id)
    }
  }

  // === _resolveTags ===
  "_resolveTags" should {
    class TSpace extends CommonSpace {
      val listOfTags = new TestProperty(Tags.NewTagSetType, QList, "List of Tags")
      val oneTag = new TestProperty(Tags.NewTagSetType, ExactlyOne, "One Tag")

      val thing1 = new SimpleTestThing("Thing 1", optTextProp("Hello"))
      val thing3 = new SimpleTestThing("Thing 3", optTextProp("world"))

      val myThing = new SimpleTestThing("My Thing", listOfTags("Thing 1", "Thing 2", "Thing 3"), oneTag("Thing 1"))
      val unresolvedThing = new SimpleTestThing("Unresolved", listOfTags("Floobity", "Smurf", "fnord"))
    }

    "work normally" in {
      implicit val s = new TSpace

      pql("""[[My Thing -> List of Tags -> _resolveTags]]""") should
        equal(listOfLinkText(s.thing1, s.thing3))
      pql("""[[My Thing -> One Tag -> _resolveTags]]""") should
        equal(linkText(s.thing1))

      pql("""[[Unresolved -> List of Tags -> _resolveTags]]""") should
        equal("")
    }

    // .3y28bsu -- _resolveTags becomes essentially redundant in most cases, since
    // you can now access Properties directly through the Tag:
    "not be necessary for Property Access" in {
      implicit val s = new TSpace

      pql("""[[My Thing -> List of Tags -> My Optional Text -> _commas]]""") should
        equal("Hello, world")
    }
  }

  // === _tagRefs ===
  "_tagRefs" should {
    "work with a single result" in {
      implicit val s = new CDSpace

      pql("""[[Weird -> _tagRefs]]""") should
        equal(listOfLinkText(s.tmbg))
    }

    "work with multiple results" in {
      implicit val s = new CDSpace

      pql("""[[Rock -> _tagRefs -> _sort]]""") should
        equal(listOfLinkText(s.blackmores, s.eurythmics, s.tmbg))
    }

    // Test for Issue .3y286st
    "work with multiple Tags" in {
      implicit val s = new CDSpace

      pql("""[[_concat(Pop, Weird) -> _tagRefs -> _sort]]""") should
        equal(listOfLinkText(s.tmbg, s.whitney))
    }

    // Test for Issue .3y285gi
    "find references from inside Model Types" in {
      class TSpace extends CommonSpace {
        val tagProp = new TestProperty(Tags.NewTagSetType, QSet, "My Tag Prop")

        val subModel = new SimpleTestThing("SubModel", tagProp())
        val propOfModelType = TestModelProperty("Complex Prop", subModel, Optional)

        val midModel = new SimpleTestThing("MidModel", propOfModelType())
        val propOfMidModelType = TestModelProperty("Deep Prop", midModel, Optional)

        val topModel = new SimpleTestThing("My Model", propOfModelType(), propOfMidModelType(), tagProp())
        val instance1 = new TestThing("Thing 1", topModel, tagProp("Tag 1"))
        val instance2 = new TestThing("Thing 2", topModel, propOfModelType(SimplePropertyBundle(tagProp("Tag 2"))))
        val instance3 = new TestThing(
          "Thing 3",
          topModel,
          propOfMidModelType(SimplePropertyBundle(propOfModelType(SimplePropertyBundle(tagProp("Tag 2")))))
        )
      }
      implicit val s = new TSpace

      pql("""[[Tag 1 -> _tagRefs]]""") should
        equal(listOfLinkText(s.instance1))
      pql("""[[Tag 2 -> _tagRefs -> _sort]]""") should
        equal(listOfLinkText(s.instance2, s.instance3))
    }

    // Test for Issue .3y286oo
    "work with a specified Property" in {
      class TSpace extends CommonSpace {
        val tagProp1 = new TestProperty(Tags.NewTagSetType, QSet, "First Tag Prop")
        val tagProp2 = new TestProperty(Tags.NewTagSetType, QSet, "Second Tag Prop")

        val targetThing = new SimpleTestThing("Target Thing")

        val source1 = new SimpleTestThing("Source 1", tagProp1("Target Thing"))
        val source2 = new SimpleTestThing("Source 2", tagProp2("Target Thing"))
        val source3 = new SimpleTestThing("Source 3", tagProp1("Target Thing"))
        val source4 = new SimpleTestThing("Source 4", tagProp2("Target Thing"))
      }
      implicit val s = new TSpace

      pql("""[[Target Thing -> _tagRefs -> _sort]]""") should
        equal(listOfLinkText(s.source1, s.source2, s.source3, s.source4))
      pql("""[[Target Thing -> First Tag Prop._tagRefs -> _sort]]""") should
        equal(listOfLinkText(s.source1, s.source3))
      pql("""[[Target Thing -> Second Tag Prop._tagRefs -> _sort]]""") should
        equal(listOfLinkText(s.source2, s.source4))
    }

    // Test for Issue .3y2874q
    "work with a different Link Name than Display Name" in {
      class TSpace extends CommonSpace {
        val named = new SimpleTestThing("My Link Name", Basic.DisplayNameProp("My Display Name"))
        val pointer = new SimpleTestThing("Pointing Thing", singleTagProp("My Link Name"))
      }
      implicit val s = new TSpace

      pql("""[[My Link Name -> _tagRefs]]""") should
        equal(listOfLinkText(s.pointer))
    }

    // Test for Issue .3y28b65
    "find tags on Properties" in {
      class TSpace extends CommonSpace {
        val propTagProp = new TestProperty(TagType, ExactlyOne, "Prop Categories")
        val myProp = new TestProperty(TextType, ExactlyOne, "Prop With a Tag", propTagProp("My Prop Tag"))
      }
      implicit val s = new TSpace

      pql("""[[Prop Categories._self -> _tagsForProperty -> _tagRefs]]""") should equal(listOfLinkText(s.myProp))
    }
  }

  // === _tagsForProperty ===
  "_tagsForProperty" should {
    "work for a simple Tag" in {
      implicit val s = new CDSpace

      pql("""[[Genres._tagsForProperty -> _sort -> _commas]]""") should
        equal("[Folk](Folk), [Parody](Parody), [Pop](Pop), [Rock](Rock), [Weird](Weird)")
    }

    "work with a received context" in {
      implicit val s = new CDSpace

      pql("""[[Genres._self -> _tagsForProperty -> _sort -> _commas]]""") should
        equal("[Folk](Folk), [Parody](Parody), [Pop](Pop), [Rock](Rock), [Weird](Weird)")
    }

    // Test for Issue .3y286so
    "work for Tags embedded in Model Types" in {
      // This example is adapted from the Cooks Guild Space, where it came up:
      class TSpace extends CommonSpace {
        val source = new SimpleTestThing("Source")
        val secondarySourceProp = new TestProperty(Tags.NewTagSetType, ExactlyOne, "Secondary Source")
        // Yes, we have an empty ExactlyOne in the Model. That's a bit suspicious, and worth thinking about:
        val secondarySourceDetails = new SimpleTestThing("Secondary Source Details", secondarySourceProp())
        val secondarySources = TestModelProperty("Secondary Sources", secondarySourceDetails, QList)

        val recon1 = new SimpleTestThing(
          "Recon1",
          secondarySources(SimplePropertyBundle(secondarySourceProp("Medieval Arab Cookery")))
        )
        val recon2 = new SimpleTestThing(
          "Recon2",
          secondarySources(SimplePropertyBundle(secondarySourceProp("Medieval Arab Cookery", "Pleyn Delit")))
        )
        val recon3 = new SimpleTestThing(
          "Recon3",
          secondarySources(SimplePropertyBundle(secondarySourceProp("The Medieval Kitchen")))
        )
        val recon4 =
          new SimpleTestThing("Recon4", secondarySources(SimplePropertyBundle(secondarySourceProp("Pleyn Delit"))))
      }
      implicit val s = new TSpace

      pql("""[[Secondary Source._tagsForProperty -> _sort]]""") should
        equal(listOfTags("Medieval Arab Cookery", "Pleyn Delit", "The Medieval Kitchen"))
    }
  }
}

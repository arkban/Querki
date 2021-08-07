package querki.text

import models.PropMap
import models.ModelPersistence
import ModelPersistence._
import querki.globals._
import querki.test._

/**
 * @author jducoeur
 */
class TextTests extends QuerkiTests with ModelPersistence with querki.types.ModelTypeDefiner {
  "_matchCase" should {
    "work in simple cases" in {
      class TSpace extends CommonSpace {
        val matchFunc = new TestProperty(Basic.QLType, ExactlyOne, "Matcher")
        val textThing =
          new SimpleTestThing("Text Thing", optTextProp("something"), matchFunc("My Optional Text -> _matchCase"))
      }
      implicit val s = new TSpace

      pql("""[[Text Thing -> Matcher]]""") should equal("Something")
      pql("""[[Text Thing -> matcher]]""") should equal("something")
    }

    "work with one explicit level" in {
      class TSpace extends CommonSpace {
        val matchFunc = new TestProperty(Basic.QLType, ExactlyOne, "Matcher")
        val textThing =
          new SimpleTestThing("Text Thing", optTextProp("something"), matchFunc("My Optional Text -> _matchCase(1)"))
      }
      implicit val s = new TSpace

      pql("""[[Text Thing -> Matcher]]""") should equal("Something")
      pql("""[[Text Thing -> matcher]]""") should equal("something")
    }

    "look up the stack a level" in {
      class TSpace extends CommonSpace {
        val matchFunc = new TestProperty(Basic.QLType, ExactlyOne, "Matcher")
        val caller = new TestProperty(Basic.QLType, ExactlyOne, "Caller")
        val textThing = new SimpleTestThing(
          "Text Thing",
          optTextProp("something"),
          caller("Matcher"),
          matchFunc("My Optional Text -> _matchCase(2)")
        )
      }
      implicit val s = new TSpace

      pql("""[[Text Thing -> Caller]]""") should equal("Something")
      pql("""[[Text Thing -> caller]]""") should equal("something")
    }

    // This is the motivating use case, from the LARP App:
    "go two levels including the defining context" in {
      class TSpace extends CommonSpace {
        val nominative = new TestProperty(TextType, ExactlyOne, "Nominative")
        val gender = new SimpleTestThing("Gender")
        val male = new TestThing("Male", gender, nominative("he"))

        val charGender = new TestProperty(LinkType, ExactlyOne, "Character Gender")
        val pronoun = new TestProperty(Basic.QLType, ExactlyOne, "Pronoun")
        val ze = new TestProperty(Basic.QLType, ExactlyOne, "Ze")
        val character =
          new SimpleTestThing(
            "Character",
            charGender(),
            pronoun("Character Gender -> $_defining -> _matchCase(2)"),
            ze("Nominative.Pronoun")
          )
        val joe = new TestThing("Joe", character, charGender(male))
      }
      implicit val s = new TSpace

      pql("""[[Joe -> Ze]]""") should equal("He")
      pql("""[[Joe -> ze]]""") should equal("he")
    }
  }

  "_positions" should {
    "work for a single hit" in {
      implicit val s = commonSpace

      pql("""[[""hello there"" -> _positions(""lo"")]]""") should
        equal(listOf("3"))
    }

    "work for multiple hits" in {
      implicit val s = commonSpace

      pql("""[[""lololol"" -> _positions(""lo"")]]""") should
        equal(listOf("0", "2", "4"))
    }

    "work for no hits" in {
      implicit val s = commonSpace

      pql("""[[""the quick brown cow"" -> _positions(""lo"")]]""") should
        equal(listOf())
    }

    "let me easily test for the existence of a substring" in {
      implicit val s = commonSpace

      pql("""[[""the quick brown cow"" -> _positions(""lo"") -> _isEmpty]]""") should
        equal("true")
      pql("""[[""lololol"" -> _positions(""lo"") -> _isEmpty]]""") should
        equal("false")
    }

    "cope with an empty input" in {
      implicit val s = commonSpace

      pql("[[\"\"\"\" -> _positions(\"\"lo\"\")]]") should
        equal(listOf())
    }

    "error on an empty substring" in {
      implicit val s = commonSpace

      pql("[[\"\"the quick brown cow\"\" -> _positions(\"\"\"\")]]") should
        equal(expectedWarning("Text.positions.subEmpty"))
    }
  }

  "_substring" should {
    "allow oid manipulation" in {
      implicit val s = commonSpace

      pql("[[Sandbox -> _oid -> _substring(1)]]") should
        // OID.toString already lacks the dot at the front:
        equal(s.sandbox.id.toString())
    }

    "work with one parameter" in {
      implicit val s = commonSpace

      pql("""[[""FooBar"" -> _substring(3)]]""") should
        equal("Bar")
    }

    "work with two parameters" in {
      implicit val s = commonSpace

      pql("""[[""FooBar"" -> _substring(1, 4)]]""") should
        equal("ooB")
    }

    "cope with parameters that are out of bounds" in {
      implicit val s = commonSpace

      pql("""[[""FooBar"" -> _substring(1, 7)]]""") should
        equal("ooBar")
      pql("""[[""FooBar"" -> _substring(7, 10)]]""") should
        equal("")
    }
  }

  "_textLength" should {
    "work with literals" in {
      implicit val s = commonSpace

      pql("""[[""Hello"" -> _textLength]]""") should equal("5")
    }

    "work with the Name Property" in {
      implicit val s = commonSpace

      pql("""[[My Instance -> Link Name -> _textLength]]""") should equal("11")
    }

    "work with a Number" in {
      implicit val s = commonSpace

      pql("""[[1827 -> _textLength]]""") should equal("4")
    }

    "work with a literal parameter" in {
      implicit val s = commonSpace

      pql("""[[_textLength(""hello"")]]""") should equal("5")
    }

    "work with a processed parameter" in {
      implicit val s = commonSpace

      pql("""[[My Instance -> _textLength(Link Name)]]""") should equal("11")
    }
  }

  "_toCase" should {
    "work as expected" in {
      implicit val s = commonSpace

      pql("""[[""the Quick brown FOX"" -> _toCase(Upper)]]""") should equal("THE QUICK BROWN FOX")
      pql("""[[""the Quick brown FOX"" -> _toCase(UPPER)]]""") should equal("THE QUICK BROWN FOX")
      pql("""[[""the Quick brown FOX"" -> _toCase(Lower)]]""") should equal("the quick brown fox")
      pql("""[[""the Quick brown FOX"" -> _toCase(CapFirst)]]""") should equal("The Quick brown FOX")
      pql("""[[""the Quick brown FOX"" -> _toCase(Thingy)]]""") should equal(expectedWarning("Text.toCase.badOption"))
    }
  }

  "Sets of Text" should {
    // Regression test for QI.bu6oc4a
    "serialize properly with a length-1 Set of empty Text" in {
      class TextTestSpace extends CommonSpace {
        val textSet = new TestProperty(Core.TextType, QSet, "Further Discussion")

        val testThing = new SimpleTestThing("Test Thing", textSet(""))
      }
      val s = new TextTestSpace
      implicit val state = s.state

      // TODO: most of this is kind of lifted from ModelPersistenceTests. Should probably be
      // refactored.
      val original = s.testThing.props
      val dh: DHPropMap = original
      val copy: PropMap = dh

      def checkProp(prop: AnyProp) = {
        assert(prop.from(copy).matches(prop.from(original)))
      }
      checkProp(s.textSet)
    }
  }

  "Text Properties" should {
    // Regression test for QI.7w4g9o9:
    "be callable with parameters" in {
      class TSpace extends CommonSpace {
        val textProp = new TestProperty(Core.TextType, ExactlyOne, "Text With Param")
        val testThing = new SimpleTestThing("Test Thing", textProp("The parameter is [[$_1]] -- correct?"))
      }
      implicit val s = new TSpace

      pql("""[[Test Thing -> Text With Param(""hello"")]]""") should equal("The parameter is hello -- correct?")
    }
  }
}

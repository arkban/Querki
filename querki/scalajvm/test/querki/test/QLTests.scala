package querki.test

import org.scalatest.{BeforeAndAfterAll, WordSpec}

class QLTests extends QuerkiTests {
  lazy val QLType = Basic.QLType

  "Arrows" should {
    "work inline" in {
      processQText(commonThingAsContext(_.sandbox), """[[My Model._instances -> _sort]]""".stripMargin) should equal(
        listOfLinkText(commonSpace.instance)
      )
    }

    "work at end of line" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances -> 
          | _sort]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }

    "work at start of line" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances 
          |-> _sort]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }
  }

  "Ordinary references" should {
    "return through the initial context" in {
      processQText(commonThingAsContext(_.withUrl), "[[My Optional URL]]") should
        equal("""[http://www.google.com/](http://www.google.com/)""")
    }

    "return through an interrupted context" in {
      processQText(commonThingAsContext(_.sandbox), "[[With URL -> My Optional URL]]") should
        equal("""[http://www.google.com/](http://www.google.com/)""")
    }
  }

  "Display names" should {
    "work with backticks" in {
      processQText(commonThingAsContext(_.sandbox), """[[`My name is "interesting"!`]]""") should
        // TBD: when we changed the way we handle LinkType rendering, we needed to change this test:
//        equal ("""[My name is &quot;interesting&quot;!](Interesting-Display-Name)""")
        equal("""[My name is "interesting"!](Interesting-Display-Name)""")
    }

    "work normally if the name is simple" in {
      class TSpace extends CommonSpace {
        val nameThing = new SimpleTestThing("NameProp name", DisplayNameProp("My Display Name"))
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.nameThing)), "[[My Display Name]]") should
        equal("""[My Display Name](NameProp-name)""")
    }
  }

  "$context references" should {
    "work trivially" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")

        val thingWithMethod = new SimpleTestThing("Methodical", myMethod("""$_context -> Link Name"""))
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[My Method]]") should
        equal("Methodical")
    }

    "work in a parameter" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")

        val thingWithMethod = new SimpleTestThing("Methodical", myMethod("""_section($_context)"""))
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[My Method]]") should
        equal("[Methodical](Methodical)")
    }

    "work as a nested parameter" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")
        val myInnerMethod = new TestProperty(QLType, ExactlyOne, "Method 2")

        val otherThing = new SimpleTestThing("Other Thing")
        val thingWithMethods = new SimpleTestThing(
          "Methodical",
          myMethod("""Other Thing -> Methodical.Method 2($_context)"""),
          myInnerMethod("""""[[$_context]]; [[$_1]]""""")
        )
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.thingWithMethods)), "[[My Method]]") should
        equal("[Other Thing](Other-Thing); [Methodical](Methodical)")
    }

    "work with a property" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")
        val myInnerMethod = new TestProperty(QLType, ExactlyOne, "Method 2")

        val numberProp = new TestProperty(Core.IntType, Optional, "My Number")

        val otherThing = new SimpleTestThing("Other Thing", numberProp(42))
        val thingWithMethods = new SimpleTestThing(
          "Methodical",
          myMethod("""My Number._self -> Methodical.Method 2(Other Thing)"""),
          myInnerMethod("""""[[$_1 -> $_context]]""""")
        )
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.thingWithMethods)), "[[My Method]]") should
        equal("42")
    }

    "work with a property _self" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")
        val myInnerMethod = new TestProperty(QLType, ExactlyOne, "Method 2")

        val numberProp = new TestProperty(Core.IntType, Optional, "My Number")

        val otherThing = new SimpleTestThing("Other Thing", numberProp(42))
        val thingWithMethods = new SimpleTestThing(
          "Methodical",
          myMethod("""My Number._self -> Methodical.Method 2(Other Thing)"""),
          myInnerMethod("""""[[$_1 -> $_context]] [[$_context._self -> Link Name]]""""")
        )
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.thingWithMethods)), "[[My Method]]") should
        equal("42 My Number")
    }

    "work when used as a complex parameter" in {
      class TSpace extends CommonSpace {
        val myQLProp = new TestProperty(QLType, ExactlyOne, "My Method")

        val myEnum = new SimpleTestThing("My Enum")
        val enum1 = new TestThing("Enum 1", myEnum)
        val enum2 = new TestThing("Enum 2", myEnum)

        val myEnumProp = new TestProperty(LinkType, ExactlyOne, "Enum Prop", Links.LinkModelProp(myEnum))

        val thingWithMethod = new SimpleTestThing(
          "Methodical",
          myQLProp(
            """_if(_equals($_context, With Enum 1 -> Enum Prop), ""You gave me Enum 1!"", ""Wrong thing! Humph!"")""".stripMargin
          )
        )
        val withEnum1 = new SimpleTestThing("With Enum 1", myEnumProp(enum1))
        val withEnum2 = new SimpleTestThing("With Enum 2", myEnumProp(enum2))
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Enum 1 -> Methodical.My Method]]") should
        equal("You gave me Enum 1!")
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Enum 2 -> Methodical.My Method]]") should
        equal("Wrong thing! Humph!")
    }
  }

  "Parameter references" should {
    "work trivially" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")

        val thingWithMethod = new SimpleTestThing("Methodical", myMethod("""$_1 -> Link Name"""))
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[My Method(Methodical)]]") should
        equal("Methodical")
    }

    "work when used as a complex parameter" in {
      class TSpace extends CommonSpace {
        val myQLProp = new TestProperty(QLType, ExactlyOne, "My Method")

        val myEnum = new SimpleTestThing("My Enum")
        val enum1 = new TestThing("Enum 1", myEnum)
        val enum2 = new TestThing("Enum 2", myEnum)

        val myEnumProp = new TestProperty(LinkType, ExactlyOne, "Enum Prop", Links.LinkModelProp(myEnum))

        val thingWithMethod = new SimpleTestThing(
          "Methodical",
          myQLProp(
            """_if(_equals($_1, With Enum 1 -> Enum Prop), ""You gave me Enum 1!"", ""Wrong thing! Humph!"")""".stripMargin
          )
        )
        val withEnum1 = new SimpleTestThing("With Enum 1", myEnumProp(enum1))
        val withEnum2 = new SimpleTestThing("With Enum 2", myEnumProp(enum2))
      }
      val space = new TSpace

      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Methodical.My Method(Enum 1)]]") should
        equal("You gave me Enum 1!")
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Methodical.My Method(Enum 2)]]") should
        equal("Wrong thing! Humph!")
    }

    "work with multiple parameters" in {
      class TSpace extends CommonSpace {
        val myQLProp = new TestProperty(QLType, ExactlyOne, "My Method")

        val myEnum = new SimpleTestThing("My Enum")
        val enum1 = new TestThing("Enum 1", myEnum)
        val enum2 = new TestThing("Enum 2", myEnum)

        val myEnumProp = new TestProperty(LinkType, ExactlyOne, "Enum Prop", Links.LinkModelProp(myEnum))

        val thingWithMethod = new SimpleTestThing(
          "Methodical",
          myQLProp(
            """_if(_equals($_1, $_2 -> Enum Prop), ""Yep, that has the right value"", ""Wrong value!"")""".stripMargin
          )
        )
        val withEnum1 = new SimpleTestThing("With Enum 1", myEnumProp(enum1))
        val withEnum2 = new SimpleTestThing("With Enum 2", myEnumProp(enum2))
      }
      val space = new TSpace

      processQText(
        thingAsContext[TSpace](space, (_.thingWithMethod)),
        "[[Methodical.My Method(Enum 1, With Enum 1)]]"
      ) should
        equal("Yep, that has the right value")
      processQText(
        thingAsContext[TSpace](space, (_.thingWithMethod)),
        "[[Methodical.My Method(Enum 1, With Enum 2)]]"
      ) should
        equal("Wrong value!")
    }

    "work in the method position" in {
      class TSpace extends CommonSpace {
        val myFunc = new TestProperty(QLType, ExactlyOne, "My Method")
        val propHolder = new TestProperty(LinkType, ExactlyOne, "Prop Holder")

        val examinedThing =
          new SimpleTestThing("Examined Thing", listTagsProp("hello", "there"), propHolder(listTagsProp))

        val methodTestThing =
          new SimpleTestThing(
            "Method Test",
            singleTextProp("[[My Method(Examined Thing, Prop Holder)]]"),
            myFunc("$_1 -> $_1.$_2")
          )
      }
      implicit val s = new TSpace

      pql("""[[Method Test -> Single Text]]""") should
        equal(listOfTags("hello", "there"))
    }
  }

  "~! params" should {
    // Note that this test is intentionally similar to "work in the method position" above. But it deals
    // with the weirder and more complex case where we really, really want to evaluate the param immediately.
    // It is loosely modeled on the _search() problem that led to the creation of the "~!" syntax in the first place.
    "work properly" in {
      class TSpace extends CommonSpace {
        val myFunc = new TestProperty(QLType, ExactlyOne, "My Method")
        val propHolder = new TestProperty(LinkType, ExactlyOne, "Prop Holder")

        val holderThing = new SimpleTestThing("Holder Thing", propHolder(listTagsProp))
        val examinedThing = new SimpleTestThing("Examined Thing", listTagsProp("hello", "there"))

        val methodTestThing =
          new SimpleTestThing(
            "Method Test",
            singleTextProp("[[Holder Thing -> My Method(Examined Thing, ~!Prop Holder)]]"),
            myFunc("$_1 -> $_1.$_2")
          )
      }
      implicit val s = new TSpace

      pql("""[[Method Test -> Single Text]]""") should
        equal(listOfTags("hello", "there"))
    }
  }

  "Comments" should {
    "work as the whole body" in {
      processQText(commonThingAsContext(_.sandbox), "[[// This is a comment, which does nothing]]") should
        equal("")
    }

    "work at the beginning of a line" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances ->
          |// This is a comment
          |  _sort]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }

    "work indented" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances ->
          |  // This is a comment
          |  _sort]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }

    "work after an arrow" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances -> // This is a comment
          |  _sort]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }

    "work before an arrow" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances // This is a comment
          |  -> _sort]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }

    "work at the beginning of the expression" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[// This is a comment
          |My Model._instances ->
          |  _sort]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }

    "work at the end of the expression" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances ->
          |  _sort
          |// This is a comment]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }

    "work on same line as the end of the expression" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances ->
          |  _sort // This is a comment]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }

    "work between phrases" in {
      processQText(
        commonThingAsContext(_.sandbox),
        """[[My Model._instances -> +$foo
          |// This is a comment
          |$foo -> _sort]]""".stripMargin
      ) should equal(listOfLinkText(commonSpace.instance))
    }
  }
}

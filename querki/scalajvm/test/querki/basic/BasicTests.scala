package querki.basic

import querki.test._

class BasicTests extends QuerkiTests {
  // === _bulleted ===
  "_bulleted" should {
    "deal correctly with nested bullet lists" in {
      class TSpace extends CommonSpace {
        val listNumsProp = new TestProperty(Core.IntType, QList, "Numbers")
        
        val myModel = new SimpleTestThing("Test Model", listNumsProp())
        val thing1 = new TestThing("Thing 1", myModel, listNumsProp(23,34,23))
        val thing2 = new TestThing("Thing 2", myModel, listNumsProp(1,2,3))
        val thing3 = new TestThing("Thing 3", myModel, listNumsProp(4,6,8))
      }
      implicit val s = new TSpace
      
      pql("""[[Test Model._instances -> ""[[Link Name]][[Numbers -> _bulleted]]"" -> _bulleted]]""") should
        equal("""
			<ul>
			<li class="_bullet">
			Thing 1
			<ul>
			<li class="_bullet">
			23
			</li>
			<li class="_bullet">
			34
			</li>
			<li class="_bullet">
			23
			</li>
			</ul>
			</li>
			<li class="_bullet">
			Thing 2
			<ul>
			<li class="_bullet">
			1
			</li>
			<li class="_bullet">
			2
			</li>
			<li class="_bullet">
			3
			</li>
			</ul>
			</li>
			<li class="_bullet">
			Thing 3
			<ul>
			<li class="_bullet">
			4
			</li>
			<li class="_bullet">
			6
			</li>
			<li class="_bullet">
			8
			</li>
			</ul>
			</li>
			</ul>""".strip)
    }
  }
  
  // === Computed Name ===
  "Computed Name" should {
    "be used iff there isn't a Display Name" in {
      class TSpace extends CommonSpace {
        val linkedTo = new SimpleTestThing("Thing to Link")
        val otherLinked = new SimpleTestThing("Other Linked")
        val compModel = new SimpleTestThing(
          "Model With Computed",
          singleLinkProp(),
          Basic.ComputedNameProp("""Child of [[Single Link -> Link Name]]""")
        )

        // Shows the Computed Name, but links via OID
        val unnamed = new UnnamedThing(compModel, singleLinkProp(linkedTo))
        // Shows the Computed Name, but links via Link Name
        val named = new TestThing("My Named Thing", compModel, singleLinkProp(otherLinked))
        // Shows the Display Name, and links via Link Name
        val displayNamed = new TestThing(
          "Other Named Thing", compModel, DisplayNameProp("Display Named Thing"), singleLinkProp(linkedTo))
      }
      implicit val s = new TSpace

      pql("""[[Model With Computed._instances -> _commas]]""") should
        equal (s"[Child of Thing to Link](${s.unnamed.id.toThingId.toString}), [Display Named Thing](Other-Named-Thing), [Child of Other Linked](My-Named-Thing)")
    }

    "default to OID if it is empty" in {
      class TSpace extends CommonSpace {
        val compModel = new SimpleTestThing("Model With Computed", singleTextProp(""), Basic.ComputedNameProp("""[[Single Text]]"""))
        val veryUnnamed = new UnnamedThing(compModel)
      }
      implicit val s = new TSpace
      
      val tid = s.veryUnnamed.id.toThingId.toString
      pql("""[[Model With Computed._instances]]""") should
        equal(s"""\n[$tid]($tid)""")
    }
  }
}
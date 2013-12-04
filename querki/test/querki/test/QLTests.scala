package querki.test

import models.system._

import org.scalatest.{WordSpec, BeforeAndAfterAll}
import org.scalatest.matchers.ShouldMatchers

class QLTests extends QuerkiTests {
  // === Attachments ===
  "Photos" should {
    "self-render when linked" in {
      processQText(commonThingAsContext(_.sandbox), """[[My Photo]]""") should 
        equal ("""![My Photo](a/My-Photo)""")
    }
  }
  
  "Ordinary references" should {
    "return through the initial context" in {
      processQText(commonThingAsContext(_.withUrl), "[[My Optional URL]]") should
        equal ("""[http://www.google.com/](http://www.google.com/)""")
    }
    
    "return through an interrupted context" in {
      processQText(commonThingAsContext(_.sandbox), "[[With URL -> My Optional URL]]") should
        equal ("""[http://www.google.com/](http://www.google.com/)""")      
    }
  }
  
  "$context references" should {
    "work trivially" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")
        
        val thingWithMethod = new SimpleTestThing("Methodical", myMethod("""$_context -> Name"""))
      }
      val space = new TSpace
      
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[My Method]]") should
        equal ("Methodical")
    }
    
    "work in a parameter" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")
        
        val thingWithMethod = new SimpleTestThing("Methodical", myMethod("""_section($_context)"""))
      }
      val space = new TSpace
      
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[My Method]]") should
        equal ("[Methodical](Methodical)")
    }
    
    "work when used as a complex parameter" in {
      class TSpace extends CommonSpace {
        val myQLProp = new TestProperty(QLType, ExactlyOne, "My Method")
        
        val myEnum = new SimpleTestThing("My Enum")
        val enum1 = new TestThing("Enum 1", myEnum)
        val enum2 = new TestThing("Enum 2", myEnum)
        
        val myEnumProp = new TestProperty(LinkType, ExactlyOne, "Enum Prop", LinkModelProp(myEnum))
        
        val thingWithMethod = new SimpleTestThing("Methodical", myQLProp("""_if(_equals($_context, With Enum 1 -> Enum Prop), ""You gave me Enum 1!"", ""Wrong thing! Humph!"")""".stripMargin))
        val withEnum1 = new SimpleTestThing("With Enum 1", myEnumProp(enum1))
        val withEnum2 = new SimpleTestThing("With Enum 2", myEnumProp(enum2))
      }
      val space = new TSpace
      
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Enum 1 -> Methodical.My Method]]") should
        equal ("You gave me Enum 1!")
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Enum 2 -> Methodical.My Method]]") should
        equal ("Wrong thing! Humph!")
    }
  }
  
  "Parameter references" should {
    "work trivially" in {
      class TSpace extends CommonSpace {
        val myMethod = new TestProperty(QLType, ExactlyOne, "My Method")
        
        val thingWithMethod = new SimpleTestThing("Methodical", myMethod("""$_1 -> Name"""))
      }
      val space = new TSpace
      
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[My Method(Methodical)]]") should
        equal ("Methodical")
    }
    
    "work when used as a complex parameter" in {
      class TSpace extends CommonSpace {
        val myQLProp = new TestProperty(QLType, ExactlyOne, "My Method")
        
        val myEnum = new SimpleTestThing("My Enum")
        val enum1 = new TestThing("Enum 1", myEnum)
        val enum2 = new TestThing("Enum 2", myEnum)
        
        val myEnumProp = new TestProperty(LinkType, ExactlyOne, "Enum Prop", LinkModelProp(myEnum))
        
        val thingWithMethod = new SimpleTestThing("Methodical", myQLProp("""_if(_equals($_1, With Enum 1 -> Enum Prop), ""You gave me Enum 1!"", ""Wrong thing! Humph!"")""".stripMargin))
        val withEnum1 = new SimpleTestThing("With Enum 1", myEnumProp(enum1))
        val withEnum2 = new SimpleTestThing("With Enum 2", myEnumProp(enum2))
      }
      val space = new TSpace
      
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Methodical.My Method(Enum 1)]]") should
        equal ("You gave me Enum 1!")
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Methodical.My Method(Enum 2)]]") should
        equal ("Wrong thing! Humph!")      
    }
    
    "work with multiple parameters" in {
      class TSpace extends CommonSpace {
        val myQLProp = new TestProperty(QLType, ExactlyOne, "My Method")
        
        val myEnum = new SimpleTestThing("My Enum")
        val enum1 = new TestThing("Enum 1", myEnum)
        val enum2 = new TestThing("Enum 2", myEnum)
        
        val myEnumProp = new TestProperty(LinkType, ExactlyOne, "Enum Prop", LinkModelProp(myEnum))
        
        val thingWithMethod = new SimpleTestThing("Methodical", myQLProp("""_if(_equals($_1, $_2 -> Enum Prop), ""Yep, that has the right value"", ""Wrong value!"")""".stripMargin))
        val withEnum1 = new SimpleTestThing("With Enum 1", myEnumProp(enum1))
        val withEnum2 = new SimpleTestThing("With Enum 2", myEnumProp(enum2))
      }
      val space = new TSpace
      
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Methodical.My Method(Enum 1, With Enum 1)]]") should
        equal ("Yep, that has the right value")
      processQText(thingAsContext[TSpace](space, (_.thingWithMethod)), "[[Methodical.My Method(Enum 1, With Enum 2)]]") should
        equal ("Wrong value!")            
    }
  }
}
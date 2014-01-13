package querki.links

import models.{Kind, PTypeBuilder, Wikitext}

import querki.core.URLableType
import querki.ecology._

import querki.values.{ElemValue, QLContext}

class LinksEcot(e:Ecology) extends QuerkiEcot(e) with Links {
  import MOIDs._
  
  def LinkValue(target:OID):QValue = ExactlyOne(LinkType(target))
      
  /***********************************************
   * TYPES
   ***********************************************/
  
  lazy val ExternalLinkType = new SystemType[QURL](ExternalLinkTypeOID,
    toProps(
      setName("URL Type")
    )) with PTypeBuilder[QURL, String] with URLableType
  {
    override def editorSpan(prop:Property[_,_]):Int = 6
  
    def doDeserialize(v:String) = QURL(v)
    def doSerialize(v:QURL) = v.url
    def doWikify(context:QLContext)(v:QURL, displayOpt:Option[Wikitext] = None) = {
      val display = displayOpt.getOrElse(Wikitext(v.url))
      Wikitext("[") + display + Wikitext("](" + v.url + ")")
    }
  
    def getURL(context:QLContext)(elem:ElemValue):Option[String] = {
      elem.getOpt(this).map(_.url)
    }
  
    val doDefault = new QURL("")
    override def wrap(raw:String):valType = new QURL(raw)
  }
  
  override lazy val types = Seq(
    ExternalLinkType
  )
    
  /***********************************************
   * PROPERTIES
   ***********************************************/
  
	/**
	 * Meta-property, set on Properties of LinkType, to filter what to Link to.
	 */
	lazy val LinkKindProp = new SystemProperty(LinkKindOID, IntType, QList,
	    toProps(
	      setName("Link Kind"),
	      SkillLevel(SkillLevelAdvanced),
	      Summary("The Kind that this Property can Link to"),
	      Details("""When you create a Link Property, if you do *not* set the *Link Model* Property on it,
	          |you may want to at least specify which *Kind* of Thing this can Link to. There are five Kinds of
	          |Things in Querki:
	          |
	          |* Ordinary Thing
	          |* Property
	          |* Space
	          |* Type
	          |* Collection
	          |
	          |99% of the time, you will want to link to ordinary Things. (And most of those times, you should set
	          |a particular Link Model.) Occasionally, for very complex systems, you may want to Link to Property
	          |or Space instead. You are not likely to ever link to Type or Collection, but it is possible to do so.
	          |
	          |This is an extremely advanced property, and not intended for casual use.""".stripMargin),
	      AppliesToKindProp(Kind.Property)
	      ))
	
	lazy val LinkAllowAppsProp = new SystemProperty(LinkAllowAppsOID, YesNoType, Optional,
	    toProps(
	      setName("Allow Links to Apps"),
	      Summary("Should this Property allow Links to Things in Apps?"),
	      Details("""Normally, links are only to other Things in this Space. But if this flag is set, this
	          |says that this Property should allow linking to Things in Apps of this Space.
	          |
	          |This is an advanced property, and not intended for casual use.""".stripMargin),
	      AppliesToKindProp(Kind.Property),
	      SkillLevel(SkillLevelAdvanced)
	      ))
	
	lazy val LinkModelProp = new SystemProperty(LinkModelOID, LinkType, Optional,
	    toProps(
	      setName("Link Model"),
	      Summary("Which Things can this Property link to?"),
	      Details("""By default, Link Properties allow you to link to *anything*. This usually isn't what
	          |you want, though -- most often, you're looking for Instances of a specific Model. For example,
	          |if you specify the Stylesheet Property, you only want to give Stylesheets as options to Link to:
	          |it would be meaningless to have the Stylesheet point to something like a recipe or a to-do list.
	          |
	          |So this is a meta-Property: when you create a Property that is a Link, you can add this to say
	          |exactly what it can link *to*. It is strongly recommended that you set this on all Link Properties
	          |you create -- it makes them easier to use, and tends to prevent confusing errors.
	          |
	          |Note that this is only enforced loosely, and you can't absolutely count upon this restriction
	          |always being true. But used properly, it will steer folks in the right direction.""".stripMargin),
	      AppliesToKindProp(Kind.Property),
	      LinkToModelsOnlyProp(true)
	      ))
	
	// TODO: As it says, replace this with a more general Link Filter property. That will need bigger
	// refactorings, though: I started to build that, only to discover that SpaceState.linkCandidates
	// doesn't have all the request-context information needed to resolve a QL Expression.
	lazy val LinkToModelsOnlyProp = new SystemProperty(LinkToModelsOnlyOID, YesNoType, ExactlyOne,
	    toProps(
	      setName("Link to Models Only"),
	      (querki.identity.skilllevel.MOIDs.SkillLevelPropOID -> ExactlyOne(LinkType(querki.identity.skilllevel.MOIDs.SkillLevelAdvancedOID))),
	      Summary("Only allow this Property to Link to Models"),
	      Details("""If set to true, this Link Property will only show Models as options to link to in the editor.
	          |
	          |This is an advanced property, and something of a hack -- don't get too comfortable with it. In the
	          |medium term, it should get replaced by a more general LinkFilter property that lets you specify which
	          |Things to link to.""".stripMargin)))
	
	lazy val NoCreateThroughLinkProp = new SystemProperty(NoCreateThroughLinkOID, YesNoType, ExactlyOne,
	    toProps(
	      setName("No Create Through Link Model"),
	      NotInherited,
	      Summary("Set this to prevent new instances from being created accidentally."),
	      Details("""When you create a Link Property in the Editor, you can set the "Link Model" -- the sort of Thing
	          |that this Property points to. The Editor then lets you choose from all of the existing Instances of that
	          |Model, and also lets you create a new one.
	          |
	          |Sometimes, though, you don't want to create any new ones from the Editor. In particular, if you've already
	          |created all of the Instances of this Model that you ever expect to want, then it is simply annoying to have
	          |that option. In that case, put this Property on your Model, and set it to True -- it will make that option
	          |in the Editor go away.""".stripMargin)))

  override lazy val props = Seq(
    LinkKindProp,
    LinkAllowAppsProp,
    LinkModelProp,
    LinkToModelsOnlyProp,
    NoCreateThroughLinkProp
  )
}
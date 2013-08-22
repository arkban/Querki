package modules.time

import com.github.nscala_time.time.Imports._

import models._
import models.system._

import Thing._

import querki.values.ContextBase

object TimeModule {

  import anorm._
  
  /**
   * Anorm extension to convert a SQL Timestamp to a Joda DateTime.
   * 
   * Simplified from: http://stackoverflow.com/questions/11388301/joda-datetime-field-on-play-framework-2-0s-anorm/11975107#11975107
   * See that post for how to convert other SQL types, but we may not bother, so I'm keeping it simple for now.
   */
  implicit def rowToDateTime: Column[DateTime] = Column.nonNull { (value, meta) =>
    val MetaDataItem(qualified, nullable, clazz) = meta
    value match {
      case ts: java.sql.Timestamp => Right(new DateTime(ts.getTime))
      //case d: java.sql.Date => Right(new DateTime(d.getTime))
      case _ => Left(TypeDoesNotMatch("Cannot convert " + value + ":" + value.asInstanceOf[AnyRef].getClass) )
    }
  }
  
  // The epoch, typically used for "We don't really have a time for this":
  val epoch = new DateTime(0)
}

/**
 * The TimeModule is responsible for all things Time-related in the Querki API.
 * 
 * Conceptually, it is a fairly thin layer over Joda-time -- Joda is pretty clean and
 * well-built, so we may as well adapt from it. When in doubt, use Joda's conceptual
 * structure.
 * 
 * As of this writing, the methods are all in System, but that should probably change:
 * I expect us to expose a *lot* of methods and types through this Module. So in the
 * medium term, most of it should become an opt-in Mixin, with only the most essential
 * methods in System. (This will likely require us to split this Module into two, which
 * should be fine.) 
 */
class TimeModule(val moduleId:Short) extends modules.Module {

  object MOIDs {
    val DateTimeTypeOID = moid(1)
    val ModifiedTimeMethodOID = moid(2)
  }
  import MOIDs._
  
    
  /******************************************
   * TYPES
   ******************************************/
  
  class QDateTime(tid:OID) extends SystemType[DateTime](tid,
      toProps(
        setName("Date and Time Type")
      )) with SimplePTypeBuilder[DateTime]
  {
    def doDeserialize(v:String) = new DateTime(v.toLong)
    def doSerialize(v:DateTime) = v.millis.toString
    val defaultRenderFormat = DateTimeFormat.mediumDateTime
    def doRender(context:ContextBase)(v:DateTime) = Wikitext(defaultRenderFormat.print(v))
    override def doComp(context:ContextBase)(left:DateTime, right:DateTime):Boolean = { left < right } 
    override def doMatches(left:DateTime, right:DateTime):Boolean = { left.millis == right.millis }
    val doDefault = TimeModule.epoch
  }
  lazy val QDateTime = new QDateTime(DateTimeTypeOID)
  override lazy val types = Seq(QDateTime)

  /***********************************************
   * PROPERTIES
   ***********************************************/
  
  lazy val modTimeMethod = new SingleThingMethod(ModifiedTimeMethodOID, "_modTime", "When was this Thing last changed?", 
      """THING -> _modTime -> Date and Time
      |This method can receive any Thing; it produces the Date and Time when that Thing was last changed.""",
      {(t:Thing, _:ContextBase) => ExactlyOne(QDateTime(t.modTime)) })

  override lazy val props = Seq(
    modTimeMethod
  )
}
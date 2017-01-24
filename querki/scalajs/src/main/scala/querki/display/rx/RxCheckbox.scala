package querki.display.rx

import org.scalajs.dom
import org.querki.jquery._
import scalatags.JsDom.all._
import _root_.rx._

import querki.globals._

class RxCheckbox(val checked:Var[Boolean], lbl:String, mods:Modifier*)(implicit val ecology:Ecology) extends Gadget[dom.html.Span] {
  val box = GadgetRef.of[dom.html.Input]
    .whenRendered { g =>
      g.elemOpt.map { e =>
        $(e).change { evt:JQueryEventObject =>
          checked() = $(e).prop("checked").asInstanceOf[Boolean]
        }        
      }
    }
  
  def doRender =
    span(
      box <= input(tpe:="checkbox", mods),
      lbl
    )
}

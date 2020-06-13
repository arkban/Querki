package querki

import querki.globals._

import models.{Collection, Property, PropertyBundle, PType, PTypeBuilder, Thing, Wikitext}

import querki.basic.PlainText
import querki.core.QLText
import querki.ecology._
import querki.util.PublicException
import querki.values.{ElemValue, EmptyValue, QFut, QLContext, QValue, SpaceState}

package object ql {

  /**
   * This trait should be used by any type that can consider itself "code" -- in particular, that wants to be
   * displayable in the _code() method. 
   */
  trait CodeType {
    def code(elem:ElemValue):String
  }

  /**
   * A QLFunction is something that can be called in the QL pipeline. It is mostly implemented
   * by Thing (with variants for Property and suchlike), but can also be anonymous -- for example,
   * when a method returns a partially-applied function.
   */
  trait QLFunction {
    def qlApplyTop(inv:Invocation, transformThing:Thing):Future[QLContext]
  }
  
  /**
   * This quietly transforms a returned InvocationValue[QValue] into a QValue, and will typically be
   * invoked at the end of the function.
   */
  implicit def inv2QValue(inv:InvocationValue[QValue])(implicit ecology:Ecology):QFut = {
    ecology.api[querki.ql.QL].inv2QValueImpl(inv)
  }
  
  trait WithFilter[T] {
    def map[R](mf:T => R):InvocationValue[R]
    def flatMap[R](mf:T => InvocationValue[R]):InvocationValue[R]
    def withFilter(g:T => Boolean):WithFilter[T]
  }
  
  /**
   * The InvocationValue Monad, which is how we query Invocations inside of a for.
   */
  trait InvocationValue[T] {
    def inv:Invocation
    
    def map[R](f:T => R):InvocationValue[R]
    def flatMap[R](f:T => InvocationValue[R]):InvocationValue[R]
    def withFilter(f:T => Boolean):WithFilter[T]
    
    // Access to the results of the computation. Most calling code should ignore this, and allow
    // inv2QValue to tie it all back together again.
    def get:Future[Iterable[T]]
    
    /**
     * Normally, when you map over an InvocationValue, it will iterate over the results, one at a time.
     * But occasionally, you want to get *all* of the results as a lump for processing. Use .all to
     * get that.
     */
    def all:InvocationValue[Iterable[T]]
    
    /**
     * Occasionally you really only want a single result to come out. For example, when you are generating
     * something like a label, you probably only want *one* label -- otherwise, you wind up with a multiplicative
     * explosion of results. This function restricts the results to just the first one found.
     * 
     * If *no* results are found, then this will be empty and skipped, as usual.
     */
    def one: InvocationValue[T]
  }
  
  /**
   * This encapsulates the call information about a single function invocation in QL. In general,
   * common functionality should get wrapped up into here.
   */
  trait Invocation {
    /**
     * Should be used by Functions that only take a single context, which should be the definingContext
     * if there is one. Returns a new Invocation that has the receivedContext and definingContext set
     * to the same value.
     * 
     * Basically, this should be used by Functions that are members of a specific Thing, and only
     * care about that Thing.
     */
    def preferDefiningContext:Invocation
    
    /**
     * Turns an arbitrary value into an InvocationValue, for use in a for comprehension.
     */
    def wrap[T](v:T):InvocationValue[T]
    
    /**
     * Produces an InvocationValue for an error.
     */
    def error[T](name:String, params:String*):InvocationValue[T]
    
    /**
     * Wraps a Future into Invocation-speak.
     */
    def fut[T](fut:Future[T]):InvocationValue[T]
    
    /**
     * Turns an Option value into an InvocationValue, so they can be used in a for comprehension together.
     */
    def opt[T](opt:Option[T], errOpt:Option[PublicException] = None):InvocationValue[T]
    
    /**
     * Turns an Iterable into an InvocationValue, so they can be used in a for comprehension together.
     */
    def iter[T](it:Iterable[T], errOpt:Option[PublicException] = None):InvocationValue[T]
    
    /**
     * Test the situation. This is essentially a runtime assertion, and can be used to check that the
     * user's code is correctly formed. The predicate should normally return true. If it does *not* return
     * true, processing will be aborted, and we'll instead return a PublicException using the given errorName
     * (which should be registered in the messages file) with the given params. Params is intentionally done
     * as a callback, for efficiency's sake.
     */
    def test(predicate: => Boolean, errorName:String, params: => Seq[Any]):InvocationValue[Boolean]
    
    /**
     * Declares the return type for the expected result. This can occasionally be crucial in order to
     * cast an empty return collection to the right type. It is strongly recommended if this function
     * can return an empty collection and you know the desired Type.
     * 
     * This returns a dummy Boolean, but that is simply to make the for comprehension work -- just assign
     * it to a dummy binding and otherwise ignore the result. (It turns out that returning Unit causes
     * things to short-circuit.)
     */
    def returnsType(pt:PType[_]):InvocationValue[Boolean]
    
    /**
     * Says what Collection is expected from this invocation.
     * 
     * By the nature of the way Querki Collections work, it is tricky to automatically suss this correctly;
     * there is a tendency for QValues to wind up as ExactlyOne if they only contain one element. This method
     * allows the Function to declare that, no really, we want this to produce a List.
     * 
     * In theory, we might be able to make this more automated, but attempts to do so have so far tended to
     * have unfortunate side-effects. So for now, we're taking this conservative approach.
     */
    def preferCollection(coll:Collection):InvocationValue[Boolean]
    
    /**
     * The simplest accessor -- simply returns the received value (context.v), unmodified. Mostly sugar for
     * wrapping that in an InvocationValue.
     */
    def contextValue:InvocationValue[QValue]

    /**
     * If the context's value is of the specified type (which need not be a PType), return
     * it cast to that type.
     */
    def contextTypeAs[T : scala.reflect.ClassTag]:InvocationValue[T]

    /**
     * This iterates over the individual elements of the received context, wrapping each one as a
     * context unto itself to make it usable.
     */
    def contextElements:InvocationValue[QLContext]
    
    /**
     * Iterates over all of the elements in the received context.
     * 
     * This should generally be used in preference to contextFirstAs -- it is more general and
     * "Querkish".
     */
    def contextAllAs[VT](pt:PType[VT]):InvocationValue[VT]
    
    /**
     * If the received context is of the specified type, returns the first element of that context
     * monadically.
     * 
     * In general, try to avoid this method, in favor of contextAllAs instead.
     */
    def contextFirstAs[VT](pt:PType[VT]):InvocationValue[VT]
    
    /**
     * Iterates over all of the received Things. (That is, this checks that the received values are
     * all LinkType, resolves them to Thing, and gives an error if any aren't Links.)
     */
    def contextAllThings:InvocationValue[Thing] = contextAllThings(context)

    /**
     * This overload of contextAllThings lets you say which context to use. It should usually be
     * used underneath contextElements.
     */
    def contextAllThings(processContext:QLContext):InvocationValue[Thing]
    
    /**
     * Iterates over all of the received Bundles. That is, you can pass *either* a Link to a Thing or a
     * ModeledPropertyBundle into here, and the rest of the code can deal with it.
     */
    def contextAllBundles:InvocationValue[PropertyBundle]
    
    /**
     * Similar to contextAllBundles, but this provides you with the Context as well.
     * 
     * Note that bundlesAndContextsForProp is *usually* more correct. Only use this when you aren't
     * resolving a real Property. (For example, _edit, which syntactically looks like a Method, but
     * really isn't.)
     */
    def contextBundlesAndContexts:InvocationValue[(PropertyBundle, QLContext)]
    
    /**
     * Given a Property that we have invoked, find the likeliest thing to invoke it *on*.
     * 
     * Conceptually, this is a bit too powerful to go here, but we need to invoke it from several different places.
     */
    def bundlesAndContextsForProp(prop:Property[_,_]):InvocationValue[(PropertyBundle, QLContext)]
    
    /**
     * Returns the first Thing in the received context.
     * 
     * In general, try to avoid this method, in favor of contextAllThings instead.
     */
    def contextFirstThing:InvocationValue[Thing]
    
    /**
     * Expects that the definingContext is a Property, and returns that.
     */
    def definingContextAsProperty:InvocationValue[Property[_,_]]
    
    /**
     * This is a bit specialized, but expects that the defining (dotted) context will be a Property
     * of the specified Type.
     * 
     * Future-proofing note: while QL syntax currently only allows a single value in this position,
     * this is written to iterate over all values found. This may become relevant as bindings become
     * more powerful in the QL language.
     */
    def definingContextAsPropertyOf[VT](targetType:PType[VT]):InvocationValue[Property[VT,_]]
    
    def definingContextAsOptionalPropertyOf[VT](targetType:PType[VT]):InvocationValue[Option[Property[VT,_]]]
    
    /**
     * Process and return the specific parameter, assuming nothing about the results.
     */
    def process(name:String, processContext:QLContext = context):InvocationValue[QValue]
    
    /**
     * Get the specified parameter's values, which should be of the given Type.
     */
    def processAs[VT](name:String, pt:PType[VT], processContext:QLContext = context):InvocationValue[VT]
    
    /**
     * Given the named parameter, which should be optional and have a default of Core.QNone, returns
     * an option of the actual value. This is how you typically deal with an optional parameter where
     * the "empty" behavior is different from the "full", so you don't just want to set a default.
     */
    def processAsOpt[VT](name:String, pt:PType[VT], processContext:QLContext = context):InvocationValue[Option[VT]]
    
    /**
     * Whereas normal processAs returns each of the matched elements one at a time, this returns *all* of them
     * as a List.
     */
    def processAsList[VT](name:String, pt:PType[VT], processContext:QLContext = context):InvocationValue[List[VT]]
    
    /**
     * Returns the numbered parameter if it exists, in raw parse-tree form. You only use this for "meta" functions that
     * are operating at the syntactic level, which aren't simply processing the parameter as usual.
     */
    def rawParam(name:String):InvocationValue[Option[QLExp]]
    
    /**
     * Similar to rawParam(), but produces an error if the parameter is not found.
     */
    def rawRequiredParam(name:String):InvocationValue[QLExp]
    
    /**
     * DEPRECATED
     * 
     * Process and return the specific parameter, assuming nothing about the results.
     */
    def processParam(paramNum:Int, processContext:QLContext = context):InvocationValue[QValue]

    /**
     * DEPRECATED
     * 
     * Get the specified parameter's first value, which should be of the given Type.
     */
    def processParamFirstAs[VT](paramNum:Int, pt:PType[VT], processContext:QLContext = context):InvocationValue[VT]
    
    /**
     * Variation of processParamFirstAs, which copes with optional parameters and lets you define a default.
     * 
     * IMPORTANT: if the actual parameter results in an empty value, the default is returned. That is, an empty
     * evaluation of the parameter is considered to be equivalent to the parameter not being specified in the
     * first place. This is crucial so that _filter() works with expressions that are sometimes empty. 
     * 
     * TODO: this should mostly go away as we switch to proper signature definitions, and named parameters. But
     * it is still used in a few unconventional situations.
     */
    def processParamFirstOr[VT](paramNum:Int, pt:PType[VT], default:VT, processContext:QLContext = context):InvocationValue[VT]
    
    /**
     * The general case for Functions that may take their first value as either an explicit parameter or
     * as the context.
     * 
     * IMPORTANT: paramNum is the *index* of the parameter, expectedParams is the expected *length*. So
     * firstParamOrContextValue, for a single-parameter Function, is equivalent to
     * processParamNofM(0, 1).
     * 
     * Produces an error if the actual number of params is less than (expectedParams - 1).
     */
    def processParamNofM(paramNum:Int, expectedParams:Int, processContext:QLContext = context):InvocationValue[QValue]
    
    /**
     * DEPRECATED
     * 
     * Returns the numbered parameter if it exists, in raw parse-tree form. You only use this for "meta" functions that
     * are operating at the syntactic level, which aren't simply processing the parameter as usual.
     */
    def rawParam(paramNum:Int):InvocationValue[QLExp]
    
    //////////////
    //
    // These are the raw fields. By and large, you should prefer *not* to use these, and some of them
    // may go away in the long run. If possible, use higher-level functions instead.
    //
    
    /**
     * The "primary" context of this invocation. This is an exact synonym for receivedContext, and is the
     * one you usually care about. 
     */
    def context:QLContext
    
    /**
     * The SpaceState of the Context.
     * 
     * IMPORTANT: if the "_space" parameter is provided to this Invocation, that Space will be used instead.
     * It can be the context's Space, or one of its Apps.
     */
    def state:SpaceState
    
    /**
     * The Context that was passed to this function. This always exists, although a few functions don't care
     * about it.
     */
    def receivedContext:QLContext
    
    /**
     * The Context that this function was defined on, which may be different from the received one.
     */
    def definingContext:Option[QLContext]
    
    /**
     * The Thing that this QL expression was defined in.
     */
    def lexicalThing:Option[PropertyBundle]
    
    /**
     * How many parameters were actually given?
     */
    def numParams:Int
    
    /**
     * The parameter list for this invocation, iff there was one.
     */
    def paramsOpt:Option[Seq[QLParam]]
  }
  
  trait QL extends EcologyInterface {        
    /**
     * Internal method, usually invoked implicitly by inv2QValue.
     */
    def inv2QValueImpl(inv:InvocationValue[QValue]):QFut
    
    /**
     * The guts of inv2QValueImpl, which is occasionally useful for relatively low-level power functions. 
     */
    def collectQVs(qvs:Iterable[QValue], returnType:Option[PType[_]], preferredColl:Option[Collection]):QValue
    
    /**
     * The primary entry point for processing a body of QLText into Wikitext.
     * 
     * The input text should be a block of QLText (with the text on the "outside"). This parses
     * that, uses the given context and params to process it, and returns the resulting Wikitext.
     */
    def process(input:QLText, ci:QLContext, invOpt:Option[Invocation] = None, 
        lexicalThing:Option[PropertyBundle] = None, lexicalProp:Option[AnyProp] = None):Future[Wikitext]
    
    /**
     * Process a QL Function into a QValue.
     * 
     * The input text should be a block of QL (with any text on the "inside"). This parses that,
     * uses the given context and params to process it, and returns the resulting QValue.
     * 
     * IMPORTANT: by its nature, this one only returns *one* QValue, from the last phrase. This should eventually become
     * a Seq of them instead.
     */
    def processMethod(input:QLText, ci:QLContext, invOpt:Option[Invocation] = None, 
        lexicalThing:Option[PropertyBundle] = None, lexicalProp:Option[AnyProp] = None):Future[QValue]
    
    /**
     * Process a QL Function all the way to Wikitext.
     * 
     * Note that this version copes with the entire Expression, not just the last phrase.
     */
    def processMethodToWikitext(input:QLText, ci:QLContext, invOpt:Option[Invocation] = None, 
        lexicalThing:Option[PropertyBundle] = None, lexicalProp:Option[AnyProp] = None,
        initialBindings:Option[Map[String, QValue]] = None):Future[Wikitext] 
        
    /**
     * Just parses the given text, with no further processing.
     * 
     * This is semi-internal, and relates to the incestuous relationship between QLParser
     * and QLContext.
     */
    def parseMethod(input:String):Option[QLPhrase]
    
    /**
     * Serializes the environment of the given Invocation. Intended for uses like
     * _QLButton(), where we need to be push the environment over to the Client,
     * and have it returned again.
     * 
     * The result is an opaque String, not intended for introspection.
     */
    def serializeContext(inv:Invocation, qlParamNameOpt:Option[String]):InvocationValue[String]
    /**
     * Deserializes the block from serializeContext, into the fields needed to pass into
     * processMethodToWikitext().
     */
    def deserializeContext(str:String)(implicit state:SpaceState):(QValue, Map[String, QValue])
    
    def UnknownNameType:PType[String] with PTypeBuilder[String,String]
    def ParsedTextType:PType[Wikitext] with PTypeBuilder[Wikitext,Wikitext]
    def ErrorTextType:PType[PlainText] with PTypeBuilder[PlainText,String]
    def ClosureType:PType[QLClosure] with PTypeBuilder[QLClosure,QLClosure]
    
    def WarningValue(msg:String):QValue
    def ErrorValue(msg:String):QValue
    def WikitextValue(wikitext:Wikitext):QValue
    
    def WarningFut(msg:String):Future[QValue] = Future.successful(WarningValue(msg))
    
    def EmptyListCut():QValue
  }

  /**
   * The various bits and pieces involved in defining formal function signatures.
   */
  trait Signature extends EcologyInterface {
    /**
     * A marker that should only be used in Signatures, which means "any Type can go here". If used in
     * returns, means "the same Type that was received".
     */
    def AnyType:PType[Unit]
    
    /**
     * The main point of Signature: lets you define the signature -- that is, the expected parameters -- of an InternalMethod.
     * Actually generates a SignatureProp entry, but you usually don't need to worry about that.
     * 
     * In theory, every InternalMethod should use Signature. In practice, we're not going to be hard-assed about
     * that yet, but new ones should use it.
     * 
     * @param expected Description of what is expected, and the PTypes that this Function accepts. If empty, it accepts *all* PTypes.
     *   If the received context is ignored, this should be set to None.
     * @param reqs The required parameters for this Function, in order.
     * @param opts The optional parameters for this Function, in order.
     * @param returns The PType returned by this Function, and documentation about what is produced. If the returned PType
     *   is the same as what was received, use AnyType.
     * @param defining Iff this function uses the defining context, info about that. The first field should be true iff the
     *   defining context is *required*; the second is the expected types; the third is documentation. Iff this function does
     *   not use the defining context, leave this as None.
     */
    def apply(
      expected:Option[(Seq[PType[_]], String)] = None,
      reqs:Seq[(String, PType[_], String)] = Seq.empty,
      opts:Seq[(String, PType[_], QValue, String)] = Seq.empty,
      returns:(PType[_], String) = (AnyType, ""),
      defining:Option[(Boolean, Seq[PType[_]], String)] = None
    ):(OID, QValue)
  }
}

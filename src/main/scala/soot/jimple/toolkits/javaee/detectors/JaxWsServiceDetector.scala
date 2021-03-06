/*
    This file is part of Soot entry point creator.

    Soot entry point creator is free software: you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Soot entry point creator is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with Soot entry point creator.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2013-2015 Ecole Polytechnique de Montreal & Tata Consultancy Services
 */
package soot.jimple.toolkits.javaee.detectors

import java.net.{MalformedURLException, URL}
import java.nio.file.{Files, Path, Paths}
import javax.xml.bind.{JAXB, JAXBContext}

import ca.polymtl.gigl.casi.Logging
import org.jcp.xmlns.javaee.HandlerChainsType
import soot._
import soot.jimple.toolkits.javaee.JaxWsAnnotations._
import soot.jimple.toolkits.javaee.WebServiceRegistry
import soot.jimple.toolkits.javaee.model.servlet.Web
import soot.jimple.toolkits.javaee.model.ws.{WebService, WsServlet, _}
import soot.util.ScalaWrappers._
import soot.util.SootAnnotationUtils._

import scala.collection.JavaConverters._


/**
 * Utilities to determine the values of JAX-WS services' attributes
 * @author Marc-André Laverdière-Papineau
 **/
object JaxWSAttributeUtils extends Logging {

  private lazy val handlerChainJaxbContext = JAXBContext.newInstance("org.jcp.xmlns.javaee")

  /**
   * Reverses a package name, so that e.g. scala.collection.mutable becomes mutable.collection.scala
   * @param pkg the package name
   * @return the reversed package name
   */
  def reversePackageName(pkg: String): String = {
    pkg.split("\\.").reverse.mkString(".")
  }

  /**
   * Checks if a class has all methods implemented from another as follows:
   *
   * m = methods of the reference
   * c = concrete methods of the implementor
   *
   * This method checks that m intersection c = m.
   *
   * This definition means that the implementor may have additional methods that are not defined
   * in the reference that will not affect the result of this function.
   *
   * @param reference the reference class - all its method must be implemented by the implementor
   * @param implementor the implementor class.
   * @return true if the criteria is met, false otherwise.
   */
  def implementsAllMethods(implementor: SootClass, reference: SootClass): Boolean = {
    val referenceMethodSignatures = reference.methods.map(_.getSubSignature)
    val implementorMethodSignatures = implementor.methods.map(_.getSubSignature).toSet
    referenceMethodSignatures.forall(implementorMethodSignatures.contains)
  }


  /**
   * Determines the local name of the service. This is not the same as the service name
   * JSR 181 section 4.1.1 Default: short name of the class or interface.
   * Pratically, we chop any ending 'Impl' because this is what JBossWS does.
   * @param sc the implementation class
   * @param annotationElems the annotations on the class
   * @return a non-empty string with the name of the service
   */
  def localName(sc: SootClass, annotationElems: Map[String, Any]): String = {

    //See JBossWS WebResult test case - it looks like JBoss has an heuristic when the class ends with 'Impl'
    val classNameNoImpl = if (sc.shortName.endsWith("Impl")) sc.shortName.dropRight(4)
    annotationElems.getOrElse("name", classNameNoImpl).toString
  }

  /**
   * Determines the WSDL location, as a local URL only.
   *
   * JAX-WS 2.2 Rev a sec 5.2.5 p.71 Default is the empty string
   * 5.2.5.1 p.77 WSDL needed only if SOAP 1.1/HTTP Binding
   * 5.2.5.3 WSDL is not generated on the fly, but package with the application
   * Practically, it gets mapped to http://host:port/approot/servicename?wsdlTODO: find the official spec for that
   * @param serviceName the name of the service
   * @param annotationElems the annotations on the implementing class
   *
   * @return a non-empty string
   */
  def wsdlLocation(serviceName: String, annotationElems: Map[String, Any]): String = {
    annotationElems.getOrElse("wsdlLocation", serviceName + "?wsdl").asInstanceOf[String]
  }

  /**
   * Determines the target namespace
   *
   * JAX-WS 2.2 Rev a sec 3.2 p.33-34
   * If the namespace is not specified for the service name, check for the service interface
   * A default value for the targetNamespace attribute is derived from the package name as follows:
   * 1. The package name is tokenized using the “.” character as a delimiter.
   * 2. The order of the tokens is reversed.
   * 3. The value of the targetNamespace attribute is obtained by concatenating “http://”to the list of
   * tokens separated by “ . ”and “/”.
   *
   * @param sc the implementation class
   * @param annotationElems the annotations on the implementation class
   * @return a non-empty string
   */
  def targetNamespace(sc: SootClass, annotationElems: Map[String, Any]): String = {
    annotationElems.getOrElse("targetNamespace", "http://" + reversePackageName(sc.getPackageName) + "/").toString
  }

  /**
   * Determines the port name
   *
   * JAX-WS 2.2 Rev a sec 3.11 p.54 In the absence of a portName element, an implementation
   * MUST use the value of the name element of the WebService annotation, if present, suffixed with
   * “Port”. Otherwise, an implementation MUST use the simple name of the class annotated with WebService
   * suffixed with “Port”.
   *
   * @param name the name of the service
   * @param annotationElems the annotations on the implementing class
   *
   * @return a non-empty string
   */
  def portName(name: String, annotationElems: Map[String, Any]): String = {
    annotationElems.getOrElse("portName", name + "Port").asInstanceOf[String]
  }

  /**
   * Determines the service name
   *
   * JAX-WS 2.2 Rev a sec 3.11 p.51
   * In mapping a @WebService-annotated class (see 3.3) to a wsdl:service, the serviceName element
   * of the WebService annotation are used to derive the service name. The value of the name attribute of
   * the wsdl:service element is computed according to the JSR-181 [15] specification. It is given by the
   * serviceName element of the WebService annotation, if present with a non-default value, otherwise the
   * name of the implementation class with the “Service”suffix appended to it.
   * Translation:
   * - if serviceName is set, use that
   * - if name is set, use that + "Service"
   * - if name is not set, use the short class name + "Service"
   * Since name is defaulted to the short name, we can ignore the last rule
   *
   * @param name the name of the serice
   * @param annotationElems the annotations on the implementation class
   * @return a non-empty string
   */
  def serviceName(name: String, annotationElems: Map[String, Any]): String = {
    annotationElems.getOrElse("serviceName", name + "Service").toString
  }

  def operationName(methodName: String, annotationElems: Map[String, Any]): String = {
    if (methodName.endsWith("Async"))
      annotationElems.get("operationName").map(_ + "Async").getOrElse(methodName).toString
    else
      annotationElems.getOrElse("operationName", methodName).toString

  }

  /**
   * Helper function that tries to read the file on that class - if it is an URL
   * @param sc the soot class we are dealing with - used in logging
   * @param file the handler configuration file location
   * @return an Option over the handler chain XML type
   **/
  private def handlerChainAsURL(sc: SootClass, file: String): Option[HandlerChainsType] = {
    try {
      val url = new URL(file)
      logger.info("For class {}, handler file is located at: {}", sc, url)
      val jc = JAXBContext.newInstance("org.jcp.xmlns.javaee")
      val unmarshaller = jc.createUnmarshaller()
      Some(unmarshaller.unmarshal(url).asInstanceOf[HandlerChainsType])
    } catch {
      case _: MalformedURLException => None
    }
  }

  /**
   * Helper function that tries to read the file on that class - and guesses where it could be.
   * It tries to find that file in `resourceLookupRoots` and the current working directory.
   * @param sc the soot class we are dealing with - used in logging
   * @param file the handler configuration file location
   * @param resourceLookupRoots all the places where to look up the file specified in the @HandlerChain annotation.
   * @return an Option over the handler chain XML type
   **/
  private def handlerChainAsFile(sc: SootClass, file: String, resourceLookupRoots: Traversable[Path]): Option[HandlerChainsType] = {
    logger.trace("Checking annotations: {}", sc.tags)
    val annotationElems = findJavaAnnotation(sc, HANDLER_CHAIN_ANNOTATION).map(annotationElements)
    annotationElems.flatMap(_.get("file")).map(_.toString) match {
      case None => logger.trace("No handler path annotation found in class {}", sc.name); None
      case Some(location) =>
        logger.trace("Handler file location: {}", location)
        //Paths can be expressed as absolute from the war deployment, which is useless for us
        //Transform those to relative paths and look for them in the possible places
        val locAsPath = Paths.get(if (location.startsWith("/")) location.drop(1) else location)
        val allPossibleRoots = resourceLookupRoots.toSeq :+ Paths.get(".")
        val possiblePaths = allPossibleRoots.map(_.resolve(locAsPath).toAbsolutePath)

        possiblePaths.find(Files.exists(_)) match {
          case Some(handlerFile) =>
            logger.info("For class {}, handler file is located at: {}", sc, handlerFile.toRealPath())
            val unmarshalled = JAXB.unmarshal(handlerFile.toFile, classOf[HandlerChainsType])
            Some(unmarshalled)
          case None => logger.warn("For class {}, handler file {} could not be found in : {}", sc, location, allPossibleRoots.mkString(", "))
            None
        }
    }

  }

  /**
   * Helper function that tries to read the file on that class
   * @param sc the soot class we are dealing with - used in logging
   * @param file the handler configuration file location
   * @param resourceLookupRoots all the places where to look up the file specified in the @HandlerChain annotation.
   * @return an Option over the handler chain XML type
   **/
  private def handlerChain(sc: SootClass, file: String, resourceLookupRoots: Traversable[Path]): Option[HandlerChainsType] = {
    val isUrl = handlerChainAsURL(sc, file)
    if (isUrl.isDefined)
      isUrl
    else
      handlerChainAsFile(sc, file, resourceLookupRoots)
  }


  /**
   * Checks if the given class has the @HandlerChain annotation.
   * If so, it retrieves the file specified in the annotation
   * and tries to locate it on the file system (relatively to the class' location)
   * @param sc the class to get the handlers for
   * @param resourceLookupRoots all the places where to look up the file specified in the @HandlerChain annotation.
   * @return an option to the handler chains
   **/
  def handlerChainOption(sc: SootClass, resourceLookupRoots: Traversable[Path]): Option[HandlerChainsType] = {

    for (handlerChainAnn <- findJavaAnnotation(sc, HANDLER_CHAIN_ANNOTATION);
         elements = annotationElements(handlerChainAnn);
         file <- elements.get("file").asInstanceOf[Option[String]];
         chain <- handlerChain(sc, file, resourceLookupRoots: Traversable[Path])
    ) yield chain

  }

}

import soot.jimple.toolkits.javaee.detectors.JaxWSAttributeUtils._

object JaxWsServiceDetector extends Logging {

  final val GENERATED_CLASS_NAME: String = "WSCaller"

  lazy private val responseType = Scene.v.refType("javax.xml.ws.Response")
  lazy private val futureType = Scene.v.refType("java.util.concurrent.Future")

  /**
   * Generates the `WebService` model object based on all the information provided
   * @param sc implementation class
   * @param rootPackage the root package
   * @param serviceIfaceName service interface name. Idem to `sc` for self-contained services
   * @param serviceMethods all operations implemented by this service
   * @param resourceLookupRoots all the places where to look up the file specified in the @HandlerChain annotation.
   * @return a `WebService` object wrapping all that
   */
  private def generateModel(sc: SootClass, rootPackage: String, serviceIfaceName: String,
                            annotationChain: Map[String, Any], serviceMethods: Traversable[WebMethod],
                            resourceLookupRoots: Traversable[Path]): WebService = {

    val postInitMethod: Option[String] = sc.methods.find(hasJavaAnnotation(_, WEBSERVICE_POSTINIT_ANNOTATION)).map(_.getName)
    val preDestroyMethod: Option[String] = sc.methods.find(hasJavaAnnotation(_, WEBSERVICE_PREDESTROY_ANNOTATION)).map(_.getName)

    val name: String = localName(sc, annotationChain)
    val srvcName: String = serviceName(name, annotationChain)
    val prtName: String = portName(name, annotationChain)
    val tgtNamespace: String = targetNamespace(sc, annotationChain)
    val wsdlLoc: String = wsdlLocation(srvcName, annotationChain)
    val hasAsyncAlready = serviceMethods.exists(wsm => wsm.targetMethodName.endsWith("Async") && (wsm.retType == responseType || wsm.retType == futureType))


    serviceMethods.foreach(wm => logger.trace("Web method {} hash: {}", wm, wm.hashCode(): Integer))

    // ------------- Detect handler chain on the server and parse it --------
    val handlerChainOpt = handlerChainOption(sc, resourceLookupRoots)
    if (handlerChainOpt.isDefined) {
      logger.warn("Service {} is using an handler chain. This is not supported by the analysis.", sc.name)
    }
    /* val chain : List[String] = for (
       handlerChain <- handlerChainOpt.toList;
       chain <- handlerChain.getHandlerChain.asScala;
       handler <- chain.getHandler.asScala
     ) yield handler.getHandlerClass.getValue

     if (!chain.isEmpty)
       logger.warn("Non-empty handler chain !!!!!!!!!!! {}", sc.getName)
      */

    val chain = List[String]()

    // ------------- Determine the name of the wrapper
    val wrapperName = WebService.wrapperName(rootPackage, sc.name)

    // ------------- Log and create holder object                    -------
    logger.debug("Found WS. Interface: {} Implementation: {}. Wrapper: {}. Init: {} Destroy: {} Name: {} Namespace: {} " +
      "ServiceName: {} wsdl: {} port: {}.\tMethods: {}",
      serviceIfaceName, sc.name, wrapperName, postInitMethod.getOrElse(""), preDestroyMethod.getOrElse(""), name, tgtNamespace,
      srvcName, wsdlLoc, prtName, serviceMethods, hasAsyncAlready: java.lang.Boolean
    )

    val ws = WebService(
      serviceIfaceName, sc.name, wrapperName, postInitMethod.getOrElse(""), preDestroyMethod.getOrElse(""), name, tgtNamespace,
      srvcName, wsdlLoc, prtName, chain.asJava, serviceMethods.toList.asJava, hasAsyncAlready
    )

    ws.methods.asScala.foreach(_.service = ws)
    ws
  }

  /**
   * Given a collection of methods, pick the ones that would make sense to be web service methods.
   * Practically, that means all non-static concrete methods that aren't constructors or initializers
   * @param candidates a collection of methods
   * @return a filtered collection of methods
   */
  private def filterLegitimateWebMethods(candidates : Traversable[SootMethod]) : Traversable[SootMethod] =
    candidates.filterNot(m => m.isConstructor || m.isClinit || m.isStatic).filter(_.isConcrete)

  /**
   * Extracts web service information when the interface is known
   * @param sc the implementation class
   * @param fastHierarchy the hierarchy object
   * @param rootPackage the root package
   * @param serviceInterface the service's specification interface
   * @param resourceLookupRoots all the places where to look up the file specified in the @HandlerChain annotation.
   * @return
   */
  def extractWsInformationKnownIFace(sc: SootClass, fastHierarchy: FastHierarchy,
                                     rootPackage: String, serviceInterface: SootClass, resourceLookupRoots: Traversable[Path]): WebService = {
    //Detect method names
    // JSR-181, p. 35, section 3.5 operation name is @WebMethod.operationName. Default is in Jax-WS 2.0 section 3.5
    // JAX-WS 2.2 Rev a sec 3.5 p.35 Default is the name of the method
    //TODO double-check this matching rule
    val potentialMethods = filterLegitimateWebMethods(sc.methods)

    //JBOSS-WS Test case in org.jboss.test.ws.jaxws.samples.webservice has no @WebMethod annotation on either interface nor implementation class
    val serviceMethods: Traversable[WebMethod] = for (
      sm <- potentialMethods;
      subsig = sm.getSubSignature;
      seiMethod <- serviceInterface.methodOpt(subsig);
      //if (hasJavaAnnotation(sm,WEBMETHOD_ANNOTATION) || hasJavaAnnotation(seiMethod,WEBMETHOD_ANNOTATION));
      methodAnn = elementsForJavaAnnotation(seiMethod, WEBMETHOD_ANNOTATION) ++ elementsForJavaAnnotation(sm, WEBMETHOD_ANNOTATION);
      opName = operationName(sm.getName, methodAnn);
      wsOperationName = if (opName(0).isUpper) opName(0).toLower + opName.drop(1) else opName
    ) yield new WebMethod(service = null, name = wsOperationName,  targetMethodName = sm.name, argTypes = sm.getParameterTypes, retType = sm.returnType)

    //This form overrides the interface's with the implementation's
    val annotationChain: Map[String, Any] = elementsForJavaAnnotation(serviceInterface, WEBSERVICE_ANNOTATION) ++ elementsForJavaAnnotation(sc, WEBSERVICE_ANNOTATION)
    generateModel(sc, rootPackage, serviceInterface.name, annotationChain, serviceMethods, resourceLookupRoots)

  }

  /**
   * Extracts web service information when the WS is self-contained (i.e. has no interface at all)
   * @param sc the implementation class
   * @param rootPackage the root package
   * @param resourceLookupRoots all the places where to look up the file specified in the @HandlerChain annotation.
   * @return
   */
  def extractWsInformationSelfContained(sc: SootClass, rootPackage: String, resourceLookupRoots: Traversable[Path]): WebService = {
    val eligibleMethods = filterLegitimateWebMethods(sc.methods)

    val operations = eligibleMethods.map { sm =>
        val implAnn = elementsForJavaAnnotation(sm, WEBMETHOD_ANNOTATION)
        val opName = implAnn.getOrElse("operationName", sm.getName).asInstanceOf[String]
        val wsOperationName = if (opName(0).isUpper) opName(0).toLower + opName.drop(1) else opName
        WebMethod(service = null, targetMethodName = sm.name, name = wsOperationName, retType = sm.returnType, argTypes = sm.getParameterTypes)
    }

    val annotationElems: Map[String, Any] = elementsForJavaAnnotation(sc, WEBSERVICE_ANNOTATION)
    generateModel(sc, rootPackage, sc.name, annotationElems, operations, resourceLookupRoots)
  }

  /**
   * Determine which class is the service's interface
   * @param sc the implementation class
   * @param fastHierarchy the hierarchy object
   * @return an option to the class that specifies the WS' interface. In the case that the class is self-contained,
   *         we return Some(`sc`)
   */
  def determineSEI(sc: SootClass, fastHierarchy: FastHierarchy): Option[SootClass] = {
    val annotationElems: Map[String, Any] = elementsForJavaAnnotation(sc, WEBSERVICE_ANNOTATION)
    annotationElems.get("endpointInterface") match {
      case None =>
        //Check if the implemented interface is a WS - CXF workaround
        val interfaceWithWS: Option[SootClass] = sc.interfaces.find(hasJavaAnnotation(_, WEBSERVICE_ANNOTATION))
        interfaceWithWS orElse Some(sc)
      case Some(ei) =>
        //JSR-181, section 3.1, page 13: the implementing class only needs to implement methods in the interface
        //If it is an implementing class, then it meets that criteria for sure
        //It could also not implement the interface, but have the same signatures.
        val iface = Scene.v.getSootClass(ei.asInstanceOf[String])
        if (hasJavaAnnotation(iface, WEBSERVICE_ANNOTATION) &&
          (fastHierarchy.canStoreType(sc.getType, iface.getType) || implementsAllMethods(sc, iface))) {
          //All good. The specified interface is implemented and it has the annotation
          Some(iface)
        } else None
    }
  }

}


import soot.jimple.toolkits.javaee.detectors.JaxWsServiceDetector._

/**
 * Detector for Jax-WS 2.0 Web Services
 * @param resourceLookupRoots all the places where to look up the file specified in the @HandlerChain annotation.
 * @author Marc-André Laverdière-Papineau
 */
class JaxWsServiceDetector(resourceLookupRoots: Traversable[Path] = Traversable[Path]()) extends AbstractServletDetector with Logging {

  def this() = this(Traversable[Path]())

  override def detectFromSource(web: Web) {
    val rootPackage: String = web.getGeneratorInfos.getRootPackage
    val foundWs = findWSInApplication(rootPackage)
    if (foundWs.nonEmpty) {
      val newServlet = new WsServlet(foundWs.asJava)
      val fullName = rootPackage + "." + GENERATED_CLASS_NAME
      newServlet.setClazz(fullName)
      newServlet.setName(GENERATED_CLASS_NAME)
      web.getServlets.add(newServlet)
      web.bindServlet(newServlet, "/wscaller")
    }

    logger.info("Found {} web services, representing {} operations", foundWs.size, foundWs.map(_.methods.size).sum)

    WebServiceRegistry.services = foundWs
  }

  override def detectFromConfig(web: Web) {
    //TODO avoid redundancy with HTTPServletDetector
    //TODO handle other config files

    logger.warn("Detecting Web services from configuration files is not supported yet - switching to detection from source")
    detectFromSource(web)
    /*

logger.info("Detecting web services from web.xml.")
val webInfClassFolders = SourceLocator.v.classPath.asScala.filter(_.endsWith("WEB-INF/classes"))
val webXmlFiles = webInfClassFolders.map(new File(_).getParentFile).map(new File(_, "web.xml")).filter(_.exists())
val webRootFiles = webXmlFiles.map(_.getParentFile)

try{
val fileLoaders = webRootFiles.map(new FileLoader(_))
fileLoaders.foreach(new WebXMLReader().readWebXML(_, web))
} catch {
case e: IOException => logger.info("Cannot read web.xml:", e)
}                                      */

  }

  // ----------------------- Template part of the interface
  override def getModelExtensions: java.util.List[Class[_]] = List[Class[_]](classOf[WsServlet], classOf[WebService]).asJava

  override def getCheckFiles: java.util.List[String] = List[String]().asJava

  override def getTemplateFiles: java.util.List[String] =
    List[String]("soot::jimple::toolkits::javaee::templates::ws::WSWrapper::main",
      "soot::jimple::toolkits::javaee::templates::ws::JaxWsServiceWrapper::main").asJava

  // ------------------------ Implementation

  def findWSInApplication(rootPackage: String): List[WebService] = {
    val jaxRpcService = Scene.v.sootClass("javax.xml.rpc.Service")
    val fastHierarchy = Scene.v.getOrMakeFastHierarchy //make sure it is created before the parallel computations steps in

    //We use getClasses because of the Flowdroid integration
    val wsImplementationClasses = Scene.v().classes.filter(sc => sc.resolvingLevel() > SootClass.DANGLING && sc.isConcrete &&
                                                           hasJavaAnnotation(sc, WEBSERVICE_ANNOTATION))

    val explicitImplementations = wsImplementationClasses.flatMap(extractWsInformation(_, fastHierarchy, rootPackage)).toList

    val detectedInterfaces = explicitImplementations.map(_.interfaceName).toSet

    val wsInterfaceClasses = Scene.v().applicationClasses.filter(_.isInterface).filterNot(_.isPhantom).
      filter(hasJavaAnnotation(_, WEBSERVICE_ANNOTATION)).filterNot(sc => detectedInterfaces.contains(sc.name))

    //TODO make this one skip the ones that were already found
    val implicitImplementations = for (sc <- wsInterfaceClasses;
                                       potImpl <- fastHierarchy.interfaceImplementers(sc);
                                       impl <- extractWsInformation(potImpl, fastHierarchy, rootPackage)) yield impl


    val jaxRpcServices =
      for (interface: SootClass <- fastHierarchy.allSubinterfaces(jaxRpcService) - jaxRpcService;
           impl: SootClass <- fastHierarchy.getAllImplementersOfInterface(interface).asScala
      ) yield new WebService(interface.getName, impl.getName, interface.getName + "Wrapper")

    explicitImplementations ++ implicitImplementations ++ jaxRpcServices
  }

  def extractWsInformation(sc: SootClass, fastHierarchy: FastHierarchy,
                           rootPackage: String): Option[WebService] = {


    //Ignored annotations:
    // - @SOAPBinding: This does not change the high-level behavior
    // - @WebMethod:   We expect that the type compatibility would fix that on the client side
    // - @Addressing: JAX-WS 2.2 Rev a sec 7.14.1 Looks irrelevant
    // - @WebEndpoint: JAX-WS 2.2 Rev a sec 7.6 on generated stubs only, so that is not relevant in this part.
    // - @RequestWrapper: JAX-WS 2.2 Rev a sec 7.3 ????
    // - @ResponseWrapper: JAX-WS 2.2 Rev a sec 7.4 ????

    //TODO annotations
    // - @XmlMimeType : could cause some kinds of vulnerabilities
    // - @WebParam : could it change the binding of parameters, or is it transparent?
    // - @HandlerChain : need to parse the xml file to build the chain
    // - @ServiceMode: JAX-WS 2.2 Rev a sec 7.1 Setting to MESSAGE breaks the linking?
    // - @WebFault: JAX-WS 2.2 Rev a sec 7.2 Exceptions could mean data flow, but is it transparent on the client side?

    //Reminder of attributes.
    //JAX-WS 2.2 Rev a sec 7.11.1
    //public @interface WebService {
    //  String name() default "";
    //  String targetNamespace() default "";
    //  String serviceName() default "";
    //  String wsdlLocation() default "";
    //  String endpointInterface() default "";
    //  String portName() default "";
    //};

    determineSEI(sc, fastHierarchy) match {
      case None =>
        logger.error("Cannot process service {} because the specified interface is not implemented or not annotated", sc.getName)
        None
      case Some(selfContainedClass) if selfContainedClass == sc => Some(extractWsInformationSelfContained(sc, rootPackage, resourceLookupRoots))
      case Some(serviceInterface) => Some(extractWsInformationKnownIFace(sc, fastHierarchy, rootPackage, serviceInterface, resourceLookupRoots: Traversable[Path]))
    }

  }



}
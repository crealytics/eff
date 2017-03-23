package org.atnos.eff.macros

import scala.reflect.api.Trees
import scala.reflect.macros.blackbox

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

@compileTimeOnly("enable macro paradise to expand macro annotations")
class eff extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro EffMacros.impl
}

class EffMacros(val c: blackbox.Context) {
  import c.universe._

  def abort(msg: String) = c.abort(c.enclosingPosition, msg)

  // https://issues.scala-lang.org/browse/SI-8771
  def fixSI88771(paramss: Any) = paramss.asInstanceOf[List[List[ValDef]]].map(_.map(_.duplicate))

  def replaceContainerType(tree: Trees#Tree, newType: TypeName): AppliedTypeTree = tree match {
    case AppliedTypeTree(_, inner) => AppliedTypeTree(Ident(newType), inner)
    case other => abort(s"Not an AppliedTypeTree: ${showRaw(other)}")
  }

  def adt(name: Name) = q"${TermName(name.toString.capitalize)}"

  type Paramss = List[List[ValDef]]
  def paramssToArgs(paramss: Paramss): List[List[TermName]] =
    paramss.filter(_.nonEmpty).map(_.collect { case t@ValDef(mods, name, _, _) => name })

  // remove () if no args
  def methodCallFmt(method: c.universe.Tree, args: Seq[Seq[TermName]]) = if (args.flatten.isEmpty) method else q"$method(...$args)"

  def impl(annottees: c.Expr[Any]*): c.Expr[Any] = {
    val trees = annottees.map(_.tree).toList

    trees.headOption match {
      case Some(q"$_ trait ${tpname:TypeName}[..$_] extends { ..$earlydefns } with ..$parents { $self => ..$stats }") =>
        val (typeAlias: TypeDef, freeSType) =
          stats.collectFirst { case typeDef @ q"type $_[..$_] = MemberIn[${Ident(s)}, $_]" => (typeDef, s) }
            .getOrElse(abort(s"$tpname needs to define a type alias for MemberIn[S, A]"))

        val sealedTrait: ClassDef = stats.collectFirst {
          case cd @ q"sealed trait $name[..$_]" if name == freeSType => cd.asInstanceOf[ClassDef]
        }.getOrElse(abort(s"$tpname needs to define a sealed trait $freeSType[A]"))

        case class EffType(effectType: Tree, returnTypeTree: Tree)
        object ExpectedReturnType {
          def unapply(rt: Tree): Option[EffType] = rt match {
            case AppliedTypeTree(Ident(name), List(effectType, returnType))  if name.toString == "Eff" =>
              Some(EffType(effectType, returnType))
            case _ => None
          }
        }
        def isReturnTypeOfTypeAlias(rt: Tree): Boolean = rt match {
          case ExpectedReturnType(_) => true
          case _ => false
        }

        def nonStackParams(paramss: List[List[ValDef]]) = {
          paramss.map { params =>
            params.filter {
              case ValDef(_, _, AppliedTypeTree(Ident(name), _), _) => name != typeAlias.name
              case _ => true
            }
          }.filterNot(_.isEmpty)
        }

        // check some constraints that will result in a compiler error
        stats.foreach {
          case x:ValOrDefDef if x.mods.hasFlag(Flag.PRIVATE|Flag.PROTECTED) => c.abort(x.pos, "try using access modifier: package-private")
          case v @ ValDef(_, _, rt: TypeTree, _)       => c.abort(v.pos, s"Define the return type for:") // requires explicit return type
          case d @ DefDef(_, _, _, _, rt: TypeTree, _) => c.abort(d.pos, s"Define the return type for:") // requires explicit return type
          case v @ ValDef(mods, _, rt, _) if mods.hasFlag(Flag.MUTABLE) => c.abort(v.pos, s"var is not allow in @eff trait $tpname")
          case v @ ValDef(_, _, rt, EmptyTree)  =>
            if (!isReturnTypeOfTypeAlias(rt)) c.abort(v.pos, s"Abstract val needs to have return type ${typeAlias.name}[...], otherwise, make it non-abstract.")
          case d @ DefDef(_, _, _, _, rt, EmptyTree) =>
            if (!isReturnTypeOfTypeAlias(rt)) c.abort(d.pos, s"Abstract def needs to have return type ${typeAlias.name}[...], otherwise, make it non-abstract.")
          case _ => // no issue
        }

        val absValsDefsOps: Seq[ValOrDefDef] = stats.collect {
          case m @ DefDef(_, _, _, _, ExpectedReturnType(_), EmptyTree) => m
        }

        // --------------------------------------------------------------------------------
        // vals name with op(s) means having return type matching the defined typeAlias.
        // And vise versa, nonOp(s) means having different return type than the typeAlias.
        // --------------------------------------------------------------------------------
        val liftedOps:Seq[DefDef] = absValsDefsOps.map {
          case DefDef(_, name, tparams, paramss, ExpectedReturnType(EffType(effectType, returnType)), _) =>
            val op = {
              val args = paramssToArgs(nonStackParams(paramss)).flatten
              val rhs = q"Eff.send[${sealedTrait.name}, $effectType, $returnType](${adt(name)}(..$args))"
              val params = (if (paramss.isEmpty) List.empty else paramss)
              q"def $name[..$tparams](...$params): Eff[$effectType, $returnType] = $rhs".asInstanceOf[DefDef]
            }
            op
        }

        val concreteValsDefs: Seq[ValOrDefDef] = stats.collect {
          case m @ DefDef(_, _, _, _, _, rhs) if rhs.nonEmpty => m
        }

        val (concreteOps: Seq[DefDef], concreteNonOps: Seq[ValOrDefDef]) = {
          val (ops, nonOps) = concreteValsDefs.partition {
            case DefDef(_, _, _, _, AppliedTypeTree(Ident(outerType), _), _) => outerType == typeAlias.name
            case _ => false
          }

          // defs that contain the real implementation
          val injectOps: Seq[DefDef] = ops.map {
            case DefDef(mods, tname, tp, paramss, AppliedTypeTree(_, innerType), rhs) =>
              q"$mods def $tname[..$tp](...$paramss): Eff[..$innerType] = ${rhs}".asInstanceOf[DefDef]
          }

          (injectOps, nonOps)
        }

        val injectOpsObj = {
          q"""
            ..$concreteNonOps
            ..$liftedOps
            ..$concreteOps
           """
        }


        val genCaseClassesAndObjADT =
          absValsDefsOps.collect {
            case q"..$_ def $tname[..$tparams](...$paramss): ${AppliedTypeTree(_, returnType)} = $expr" =>
              val fixedParams = fixSI88771(paramss)
              val implicitParams = fixedParams(1).collect {
                case ValDef(_, _, AppliedTypeTree(Ident(name), Seq(Ident(stackTypeName))), _) if name == typeAlias.name => stackTypeName
              }
              val nonStackReturnTypes = returnType.filter {
                case Ident(name) if implicitParams.contains(name) => false
                case _ => true
              }
              val nonStackTypeParams = tparams.filter {
                case TypeDef(_, name, _, _) if implicitParams.contains(name) => false
                case _ => true
              }
              q"case class ${TypeName(tname.toString.capitalize)}[..$nonStackTypeParams](..${nonStackParams(fixedParams).flatten}) extends ${sealedTrait.name}[..$nonStackReturnTypes]"
          }

        val sideEffectTrait = {
          val methodsToBeImpl: Seq[DefDef] = absValsDefsOps.map {
            case q"..$mods def $name[..$tparams](...$paramss): Eff[$_, $returnType]" =>
              DefDef(mods, name, tparams.dropRight(1), nonStackParams(paramss), returnType, EmptyTree)
          }

          q"""
              trait SideEffect extends org.atnos.eff.SideEffect[${sealedTrait.name}] {
                def apply[A](fa: ${sealedTrait.name}[A]): A = fa match {
                  case ..${absValsDefsOps.map {
                    case DefDef(_, name, _, paramss, rt, _) =>
                      val binds = nonStackParams(paramss).flatMap(_.collect { case t:ValDef => Bind (t.name, Ident(termNames.WILDCARD))})
                      val args = nonStackParams(paramss).map(_.collect { case t:ValDef => Ident(t.name.toTermName) })
                      val rhs = if (args.isEmpty) q"$name" else q"$name(...${args})"
                      cq"${adt(name)}(..$binds) => $rhs"
                    case ValDef(_, name, _, _) =>
                      cq"${adt(name)} => $name"
                  }}
                }
                def applicative[X, Tr[_] : Traverse](ms: Tr[${sealedTrait.name}[X]]): Tr[X] =
                  ms.map(apply)
                def run[R, A](effects: Eff[R, A])(implicit m: ${sealedTrait.name} <= R): Eff[m.Out, A] =
                  org.atnos.eff.interpret.interpretUnsafe(effects)(this)(m)
                ..$methodsToBeImpl
              }
        """
        }

        val translateTrait =  {
          val methodsToBeImpl: Seq[DefDef] = absValsDefsOps.map {
            case q"..$mods def $name[..$tparams](...$paramss): Eff[$_, $returnType]" =>
              DefDef(mods, name, tparams.dropRight(1), nonStackParams(paramss), tq"Eff[U, $returnType]", EmptyTree)
          }

          q"""
              trait Translate[R, U] extends org.atnos.eff.Translate[${sealedTrait.name}, U] {
                def apply[X](fa: ${sealedTrait.name}[X]): Eff[U, X] = fa match {
                  case ..${absValsDefsOps.map {
                    case DefDef(_, name, _, paramss, rt, _) =>
                      val binds = nonStackParams(paramss).flatMap(_.collect { case t:ValDef => Bind (t.name, Ident(termNames.WILDCARD))})
                      val args = nonStackParams(paramss).map(_.collect { case t:ValDef => Ident(t.name.toTermName) })
                      val rhs = if (args.isEmpty) q"$name" else q"$name(...${args})"
                      cq"${adt(name)}(..$binds) => $rhs.asInstanceOf[Eff[U, X]]"
                    case ValDef(_, name, _, _) =>
                      cq"${adt(name)} => $name.asInstanceOf[Eff[U, X]]"
                  }}
                }
                ..$methodsToBeImpl
              }
        """
        }

        val functionKTrait =  {
          val methodsToBeImpl: Seq[DefDef] = absValsDefsOps.map {
            case q"..$mods def $name[..$tparams](...$paramss): Eff[$_, $returnType]" =>
              DefDef(mods, name, tparams.dropRight(1), nonStackParams(paramss), tq"M[$returnType]", EmptyTree)
          }

          q"""
              trait FunctionK[M[_]] extends cats.~>[${sealedTrait.name}, M] {
                def apply[X](fa: ${sealedTrait.name}[X]): M[X] = fa match {
                  case ..${absValsDefsOps.map {
                    case DefDef(_, name, _, paramss, rt, _) =>
                      val binds = nonStackParams(paramss).flatMap(_.collect { case t:ValDef => Bind (t.name, Ident(termNames.WILDCARD))})
                      val args = nonStackParams(paramss).map(_.collect { case t:ValDef => Ident(t.name.toTermName) })
                      val rhs = if (args.isEmpty) q"$name" else q"$name(...${args})"
                      cq"${adt(name)}(..$binds) => $rhs.asInstanceOf[M[X]]"
                    case ValDef(_, name, _, _) =>
                      cq"${adt(name)} => $name.asInstanceOf[M[X]]"
                  }}
                }
                ..$methodsToBeImpl
              }
        """
        }
        val genTrait =
          q"""
            trait ${tpname.toTypeName} {
              $sealedTrait
              $typeAlias
              ..$genCaseClassesAndObjADT
              ..$injectOpsObj
              $sideEffectTrait
              $translateTrait
              $functionKTrait
            }
        """
        val genCompanionObj =
          q"""
            object ${tpname.toTermName} extends ${tpname.toTypeName}
          """

        val gen = q"""
          $genTrait
          $genCompanionObj
        """
        println(showCode(gen))
        c.Expr[Any](gen)

      case other => c.abort(c.enclosingPosition, s"${showRaw(other)}")
    }
  }

}

package scala.scalanative
package compiler

import scala.collection.mutable
import codegen.{GenTextualLLVM, GenTextualNIR}
import linker.Linker
import nir._, Shows._
import nir.serialization._
import util.sh

final class Compiler(opts: Opts) {
  private lazy val entry =
    Global.Member(Global.Top(opts.entry), "main_class.ssnr.ObjectArray_unit")

  private lazy val pipeline = Seq(
      Seq(
          pass.LocalBoxingElimination,
          pass.DeadCodeElimination,
          pass.MainInjection,
          pass.ExternHoisting,
          pass.ModuleLowering,
          pass.RuntimeTypeInfoInjection,
          pass.AsLowering,
          pass.TraitLowering,
          pass.ClassLowering,
          pass.StringLowering,
          pass.ConstLowering,
          pass.SizeofLowering,
          pass.UnitLowering,
          pass.NothingLowering,
          pass.ExceptionLowering,
          pass.StackallocHoisting,
          pass.CopyPropagation
      )
  )

  private lazy val (links, assembly): (Seq[Attr.Link], Seq[Defn]) = {
    val companions = pipeline.flatten
    val deps       = companions.flatMap(_.depends).distinct
    val linker     = new Linker(opts.dotpath, opts.classpath)

    linker.linkClosed(entry +: deps)
  }

  private lazy val phases = {
    val ctx = Ctx(fresh = Fresh("tx"),
                  entry = entry,
                  top = analysis.ClassHierarchy(assembly))

    pipeline.map(companions => new Phase(companions.map(_.apply(ctx))))
  }

  private def codegen(assembly: Seq[Defn]): Unit = {
    val gen = new GenTextualLLVM(assembly)
    serializeFile((defns, bb) => gen.gen(bb), assembly, opts.outpath)
  }

  def apply(): Seq[Attr.Link] = {
    def loop(assembly: Seq[Defn], passes: Seq[Phase]): Seq[Defn] =
      passes match {
        case Seq()         => assembly
        case phase +: rest => loop(phase.assembly(assembly), rest)
      }

    codegen(loop(assembly, phases))

    links
  }
}

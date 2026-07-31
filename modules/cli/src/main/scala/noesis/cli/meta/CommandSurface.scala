package noesis.cli.meta

import scala.quoted.*

/** One node of the command tree the CLI ships.
  *
  * `help` is decline's own subcommand description, so the conventions in `UX.md` §2 can be checked
  * against the text the owner actually reads rather than against a transcription of it.
  */
final case class CommandNode(name: String, help: String, children: List[CommandNode]):

  /** Every path at or below this node, container commands included. */
  def paths: List[List[String]] =
    List(name) :: children.flatMap(_.paths.map(name :: _))

  /** Only the paths that are invocable on their own. `contact` is a namespace, not a command. */
  def leaves: List[List[String]] =
    if children.isEmpty then List(List(name))
    else children.flatMap(_.leaves.map(name :: _))

  def descendants: List[CommandNode] = this :: children.flatMap(_.descendants)

/** The whole surface, plus anything declared and never wired up. */
final case class Surface(commands: List[CommandNode], unreachable: List[String]):
  def paths: Set[List[String]] = commands.flatMap(_.paths).toSet
  def leaves: List[List[String]] = commands.flatMap(_.leaves)
  def nodes: List[CommandNode] = commands.flatMap(_.descendants)

/** Derives the command tree from the CLI's own definitions, at compile time.
  *
  * The tree has to come from somewhere trustworthy: a hand-maintained list drifts from the parser
  * silently, which is the exact failure the product-traceability check exists to prevent. decline's
  * `Opts` algebra would be the natural thing to walk, but its constructors — `Opts.Subcommand` and
  * the rest — are `private[decline]`, so the value cannot be inspected at runtime from outside the
  * library.
  *
  * The typed AST can be, and it is a better source than either. `Opts.subcommand` is recognized by
  * its method *symbol*, and nesting is read from the *symbol references* inside each subcommand's
  * body, so a command's own help text cannot be mistaken for structure — the hazard any textual
  * scan of this file has to work around, since `archiveCreate`'s help mentions "archive-directory"
  * and its parent is named `archive`.
  */
object CommandSurface:

  /** @param module     fully qualified name of the object declaring the subcommands
    * @param entryPoint the member composing them, `Main.main`
    */
  inline def ofModule(inline module: String, inline entryPoint: String): Surface =
    ${ ofModuleImpl('module, 'entryPoint) }

  private given nodeToExpr: ToExpr[CommandNode] with
    def apply(node: CommandNode)(using Quotes): Expr[CommandNode] =
      '{ CommandNode(${ Expr(node.name) }, ${ Expr(node.help) }, ${ Expr(node.children) }) }

  private given surfaceToExpr: ToExpr[Surface] with
    def apply(surface: Surface)(using Quotes): Expr[Surface] =
      '{ Surface(${ Expr(surface.commands) }, ${ Expr(surface.unreachable) }) }

  private def ofModuleImpl(module: Expr[String], entryPoint: Expr[String])(using
      Quotes
  ): Expr[Surface] =
    import quotes.reflect.*

    val moduleName = module.valueOrAbort
    val entryName = entryPoint.valueOrAbort
    val owner = Symbol.requiredModule(moduleName).moduleClass

    /** The right-hand side of a definition, read twice on purpose.
      *
      * `Main` is compiled separately from the suite that inspects it, so its trees arrive from
      * TASTy lazily: the *first* `symbol.tree` yields the definition without its right-hand side,
      * and only a second read sees it. Reading once derives an empty surface — and an empty
      * surface makes every check downstream pass vacuously, which is the worst way for a check to
      * fail. The retry keeps that from depending on what happened to touch the symbol first.
      */
    def body(symbol: Symbol): Option[Term] =
      def read: Option[Term] =
        symbol.tree match
          case ValDef(_, _, rhs)    => rhs
          case DefDef(_, _, _, rhs) => rhs
          case _                    => None
      if !(symbol.isValDef || symbol.isDefDef) then None else read.orElse(read)

    /** The method being applied, under any number of argument and type-argument lists. */
    def applied(term: Term): Symbol = term match
      case Apply(fun, _)     => applied(fun)
      case TypeApply(fun, _) => applied(fun)
      case other             => other.symbol

    def isSubcommand(symbol: Symbol): Boolean =
      symbol.name == "subcommand" && symbol.owner.fullName.startsWith("com.monovore.decline.Opts")

    /** String literals in one application's own argument lists, not in the operand it is given. */
    def literals(term: Term): List[String] = term match
      case Apply(fun, args) =>
        literals(fun) ++ args.collect { case Literal(StringConstant(value)) => value }
      case TypeApply(fun, _) => literals(fun)
      case _                 => Nil

    /** `Opts.subcommand(name, help)(operand)`, tolerating further parameters and default arguments. */
    def declaration(term: Term): Option[(String, String, Term)] = term match
      case Inlined(_, _, inner) => declaration(inner)
      case Block(_, expr)       => declaration(expr)
      case outer @ Apply(fun, List(operand)) if isSubcommand(applied(outer)) =>
        val strings = literals(fun)
        strings.headOption.zip(strings.drop(1).headOption).map { case (name, help) =>
          (name, help, operand)
        }
      case _ => None

    /** The composition root, looked up before anything else.
      *
      * This is not merely tidy ordering: resolving a member forces the module class to complete,
      * and `declarations` read before that completion comes back empty rather than incomplete —
      * a silently passing check instead of a failing one.
      */
    val entry = owner
      .declaredMethod(entryName)
      .headOption
      .orElse(Option(owner.declaredField(entryName)).filter(_.exists))
      .getOrElse(report.errorAndAbort(s"$moduleName declares no `$entryName`"))

    val declared: Map[Symbol, (String, String, Term)] =
      owner.declarations.flatMap { symbol =>
        body(symbol).flatMap(declaration) match
          case Some(found) => List((symbol, found))
          case None        => Nil
      }.toMap

    /** Which declared subcommands a tree mentions, in source order. */
    def references(tree: Tree): List[Symbol] =
      val accumulator = new TreeAccumulator[List[Symbol]]:
        def foldTree(seen: List[Symbol], tree: Tree)(owner: Symbol): List[Symbol] =
          val next = if declared.contains(tree.symbol) then tree.symbol :: seen else seen
          foldOverTree(next, tree)(owner)
      accumulator.foldTree(Nil, tree)(Symbol.spliceOwner).reverse.distinct

    var visited = Set.empty[Symbol]

    def node(symbol: Symbol): CommandNode =
      if visited.contains(symbol) then
        report.errorAndAbort(
          s"`${symbol.name}` is reachable by two paths; the command tree is not a tree"
        )
      visited = visited + symbol
      declared.get(symbol) match
        case None => report.errorAndAbort(s"`${symbol.name}` declares no subcommand")
        case Some((name, help, operand)) =>
          CommandNode(name, help, references(operand).map(node))

    val roots = body(entry) match
      case None => report.errorAndAbort(s"$moduleName.$entryName has no definition to inspect")
      case Some(composition) => references(composition).map(node)

    val unreachable = declared.view
      .filterKeys(symbol => !visited.contains(symbol))
      .values
      .map((name, _, _) => name)
      .toList
      .sorted

    Expr(Surface(roots, unreachable))

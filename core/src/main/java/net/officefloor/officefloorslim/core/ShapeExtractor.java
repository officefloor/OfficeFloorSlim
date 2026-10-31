package net.officefloor.officefloorslim.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import net.officefloor.officefloorslim.core.MethodShape.Param;
import net.officefloor.sinewslim.facts.FactKey;
import net.officefloor.sinewslim.javaparser.JavaFacts.Extractor;

/**
 * Reads a {@link MethodShape} from a callable's syntax tree, beside SinewSlim's facts. A nested
 * type's own members are not the callable's: their throws and catches are left out.
 */
public final class ShapeExtractor implements Extractor<MethodShape> {

	/** The key of the shape in a method's extra facts. Version 1. */
	public static final FactKey<MethodShape> KEY = FactKey.of("officefloorslim.shape.v1");

	public static final ShapeExtractor INSTANCE = new ShapeExtractor();

	private ShapeExtractor() {
	}

	@Override
	public FactKey<MethodShape> key() {
		return KEY;
	}

	@Override
	public MethodShape extract(BodyDeclaration<?> callable) {
		List<String> methodAnnotations = names(callable.getAnnotations());
		// a stereotype or @Transactional applies to the class it is on, not to the classes nested in it
		List<String> typeAnnotations = new ArrayList<>();
		Node parent = callable.getParentNode().orElse(null);
		if (parent instanceof TypeDeclaration<?> td) {
			typeAnnotations.addAll(names(td.getAnnotations()));
		}

		List<Param> params = new ArrayList<>();
		String returnType = "";
		List<String> declared = new ArrayList<>();
		boolean isPublic = false, isStatic = false;
		if (callable instanceof CallableDeclaration<?> cd) {
			for (Parameter p : cd.getParameters()) {
				params.add(new Param(p.getNameAsString(), p.getType().asString() + (p.isVarArgs() ? "..." : ""),
						names(p.getAnnotations())));
			}
			for (var t : cd.getThrownExceptions()) {
				declared.add(t.asString());
			}
			isPublic = cd.isPublic();
			isStatic = cd.isStatic();
		}
		if (callable instanceof MethodDeclaration md) {
			returnType = md.getType().asString();
		}

		List<String> caught = new ArrayList<>();
		int[] throwsStatements = new int[1];
		own(callable, caught, throwsStatements, true);

		return new MethodShape(className(callable), methodAnnotations, typeAnnotations, params, returnType, declared,
				caught, throwsStatements[0], isPublic, isStatic, codeLines(callable), tries(callable),
				switches(callable));
	}

	/**
	 * The method's code lines, as SinewSlim's length counts them ({@code JavaFacts.nloc}): from its
	 * name to its end, the first line of each token that is not whitespace or a comment, nested
	 * and anonymous types left out. So {@code codeLines.length} is SinewSlim's length.
	 */
	static int[] codeLines(BodyDeclaration<?> callable) {
		SimpleName name = callable instanceof CallableDeclaration<?> cd ? cd.getName()
				: callable instanceof CompactConstructorDeclaration ccd ? ccd.getName() : null;
		if (name == null || name.getTokenRange().isEmpty() || callable.getTokenRange().isEmpty()) {
			return new int[0];
		}
		List<Range> nested = new ArrayList<>();
		nested(callable, nested);
		TreeSet<Integer> lines = new TreeSet<>();
		JavaToken last = callable.getTokenRange().get().getEnd();
		for (JavaToken t = name.getTokenRange().get().getBegin(); t != null; t = t.getNextToken().orElse(null)) {
			if (!t.getCategory().isWhitespaceOrComment() && t.getRange().isPresent()) {
				Range r = t.getRange().get();
				if (nested.stream().noneMatch(n -> n.contains(r))) {
					lines.add(r.begin.line);
				}
			}
			if (t == last) {
				break;
			}
		}
		return lines.stream().mapToInt(Integer::intValue).toArray();
	}

	private static void nested(Node n, List<Range> out) {
		for (Node c : n.getChildNodes()) {
			if (c instanceof TypeDeclaration || (n instanceof ObjectCreationExpr && c instanceof BodyDeclaration)) {
				c.getRange().ifPresent(out::add);
			} else {
				nested(c, out);
			}
		}
	}

	/** The callable's own switch statements (a nested type's left out), in order. */
	static List<MethodShape.Switch> switches(BodyDeclaration<?> callable) {
		List<MethodShape.Switch> out = new ArrayList<>();
		collectSwitches(callable, out, true);
		return out;
	}

	private static void collectSwitches(Node n, List<MethodShape.Switch> out, boolean top) {
		if (!top && (n instanceof TypeDeclaration
				|| (n instanceof BodyDeclaration && n.getParentNode().orElse(null) instanceof ObjectCreationExpr))) {
			return;
		}
		if (n instanceof SwitchStmt s && s.getRange().isPresent()) {
			boolean hasDefault = s.getEntries().stream().anyMatch(SwitchEntry::isDefault);
			boolean arrows = s.getEntries().stream().anyMatch(e -> e.getType() != SwitchEntry.Type.STATEMENT_GROUP);
			out.add(new MethodShape.Switch(s.getRange().get().begin.line, s.getRange().get().end.line, hasDefault,
					arrows));
		}
		for (Node c : n.getChildNodes()) {
			collectSwitches(c, out, false);
		}
	}

	/** The callable's own try statements (a nested type's left out), in order. */
	static List<MethodShape.Try> tries(BodyDeclaration<?> callable) {
		List<MethodShape.Try> out = new ArrayList<>();
		collectTries(callable, out, true);
		return out;
	}

	private static void collectTries(Node n, List<MethodShape.Try> out, boolean top) {
		if (!top && (n instanceof TypeDeclaration
				|| (n instanceof BodyDeclaration && n.getParentNode().orElse(null) instanceof ObjectCreationExpr))) {
			return;
		}
		if (n instanceof TryStmt t && t.getRange().isPresent()) {
			List<MethodShape.Catch> catches = new ArrayList<>();
			for (CatchClause c : t.getCatchClauses()) {
				var type = c.getParameter().getType();
				catches.add(new MethodShape.Catch(type.isUnionType() ? type.asUnionType().getElements().size() : 1,
						c.getParameter().getNameAsString()));
			}
			out.add(new MethodShape.Try(t.getRange().get().begin.line, t.getRange().get().end.line,
					!t.getResources().isEmpty(), t.getFinallyBlock().isPresent(), catches));
		}
		for (Node c : n.getChildNodes()) {
			collectTries(c, out, false);
		}
	}

	/** The catches and throws of the callable's own body, nested types' members left out. */
	private static void own(Node n, List<String> caught, int[] throwsStatements, boolean top) {
		if (!top && (n instanceof TypeDeclaration || n instanceof LocalClassDeclarationStmt
				|| n instanceof LocalRecordDeclarationStmt
				|| (n instanceof BodyDeclaration && n.getParentNode().orElse(null) instanceof ObjectCreationExpr))) {
			return;
		}
		if (n instanceof CatchClause cc) {
			var t = cc.getParameter().getType();
			if (t.isUnionType()) {
				t.asUnionType().getElements().forEach(e -> caught.add(e.asString()));
			} else {
				caught.add(t.asString());
			}
		} else if (n instanceof ThrowStmt) {
			throwsStatements[0]++;
		}
		for (Node c : n.getChildNodes()) {
			own(c, caught, throwsStatements, false);
		}
	}

	/**
	 * The binary name of the class declaring the callable ({@code a.b.Outer$Inner}); null inside
	 * an anonymous or local class, which no configuration can name.
	 */
	static String className(Node callable) {
		List<String> parts = new ArrayList<>();
		Node child = callable;
		for (Node p = callable.getParentNode().orElse(null); p != null; child = p, p = p.getParentNode().orElse(null)) {
			if (p instanceof ObjectCreationExpr && child instanceof BodyDeclaration) {
				return null; // anonymous
			}
			if (p instanceof LocalClassDeclarationStmt || p instanceof LocalRecordDeclarationStmt) {
				return null;
			}
			if (p instanceof TypeDeclaration<?> td) {
				parts.add(0, td.getNameAsString());
			}
		}
		if (parts.isEmpty()) {
			return null;
		}
		String pkg = callable.findCompilationUnit().flatMap(cu -> cu.getPackageDeclaration())
				.map(pd -> pd.getNameAsString() + ".").orElse("");
		return pkg + String.join("$", parts);
	}

	private static List<String> names(List<AnnotationExpr> annotations) {
		List<String> out = new ArrayList<>(annotations.size());
		for (AnnotationExpr a : annotations) {
			out.add(a.getNameAsString());
		}
		return out;
	}
}

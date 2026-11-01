package net.officefloor.intellij.flow.slim;

import java.util.ArrayList;
import java.util.List;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;

/**
 * Finds the Java source of a function's method: its file's text, as the editor holds it (unsaved
 * edits included), and the line of its name. Isolated in its own class, referencing the Java PSI,
 * so callers guard it with {@code catch (Throwable)}: on IDEs without the Java module it fails to
 * load, and no function is marked.
 */
final class JavaMethodSource {

	/**
	 * A function's method in its source.
	 *
	 * @param file     the file
	 * @param text     the file's text
	 * @param nameLine the line of the method's name, from 1
	 */
	record Located(VirtualFile file, String text, int nameLine) {
	}

	private JavaMethodSource() {
	}

	/** Called under a read action; null when the method has no source in the project. */
	static Located locate(Project project, String className, String methodName) {
		if (className == null || className.isEmpty()) {
			return null;
		}
		JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
		GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
		PsiClass psiClass = facade.findClass(className, scope);
		if (psiClass == null && className.indexOf('$') >= 0) {
			psiClass = facade.findClass(className.replace('$', '.'), scope);
		}
		if (psiClass == null) {
			return null;
		}
		PsiMethod method = null;
		if (methodName != null && !methodName.isEmpty()) {
			PsiMethod[] named = psiClass.findMethodsByName(methodName, false);
			method = named.length == 1 ? named[0] : null; // overloads: which one OfficeFloor wires is not known here
		} else {
			method = solePublicMethod(psiClass);
		}
		if (method == null || method.getNameIdentifier() == null) {
			return null;
		}
		PsiElement source = method.getNavigationElement();
		PsiFile file = source.getContainingFile();
		if (!(file instanceof PsiJavaFile) || file.getVirtualFile() == null) {
			return null;
		}
		Document document = PsiDocumentManager.getInstance(project).getDocument(file);
		if (document == null) {
			return null;
		}
		PsiElement name = source instanceof PsiMethod m && m.getNameIdentifier() != null ? m.getNameIdentifier() : source;
		int line = document.getLineNumber(name.getTextOffset()) + 1;
		return new Located(file.getVirtualFile(), document.getText(), line);
	}

	/** The one public, non-constructor method of the class; null if not exactly one. */
	private static PsiMethod solePublicMethod(PsiClass psiClass) {
		List<PsiMethod> candidates = new ArrayList<>();
		for (PsiMethod method : psiClass.getMethods()) {
			if (!method.isConstructor() && method.hasModifierProperty(PsiModifier.PUBLIC)) {
				candidates.add(method);
			}
		}
		return candidates.size() == 1 ? candidates.get(0) : null;
	}
}

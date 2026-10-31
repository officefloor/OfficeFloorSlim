package net.officefloor.intellij.flow.editor;

import java.util.ArrayList;
import java.util.List;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.PsiNavigateUtil;

/**
 * Navigates to a Java class / method. Isolated in its own class so that callers
 * can reference it through a {@code catch (Throwable)} guard: on IDEs without
 * the Java module this class fails to load and navigation silently falls back
 * to the YAML declaration.
 */
final class JavaTargetNavigator {

	private JavaTargetNavigator() {
	}

	/**
	 * @return {@code true} if a target was found and navigated to.
	 */
	static boolean navigate(Project project, String className, String methodName) {
		if (className == null || className.isEmpty()) {
			return false;
		}
		JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
		GlobalSearchScope scope = GlobalSearchScope.allScope(project);
		PsiClass psiClass = facade.findClass(className, scope);
		if (psiClass == null && className.indexOf('$') >= 0) {
			// Nested class written with the binary '$' separator.
			psiClass = facade.findClass(className.replace('$', '.'), scope);
		}
		if (psiClass == null) {
			return false;
		}
		if (methodName != null && !methodName.isEmpty()) {
			for (PsiMethod method : psiClass.findMethodsByName(methodName, true)) {
				if (method.canNavigate()) {
					PsiNavigateUtil.navigate(method);
					return true;
				}
			}
		} else {
			// No explicit method: OfficeFloor uses the class's single public method.
			PsiMethod sole = solePublicMethod(psiClass);
			if (sole != null && sole.canNavigate()) {
				PsiNavigateUtil.navigate(sole);
				return true;
			}
		}
		if (psiClass.canNavigate()) {
			PsiNavigateUtil.navigate(psiClass);
			return true;
		}
		return false;
	}

	/** @return the one public, non-constructor method of the class, or {@code null} if not exactly one. */
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

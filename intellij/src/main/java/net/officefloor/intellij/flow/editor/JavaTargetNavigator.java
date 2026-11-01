package net.officefloor.intellij.flow.editor;

import java.util.ArrayList;
import java.util.List;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.PsiNavigateUtil;
import com.intellij.util.concurrency.AppExecutorUtil;

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
	 * Finds the target off the EDT (a class lookup reads the indexes, which IntelliJ forbids on the
	 * EDT), then navigates to it on the EDT; runs {@code otherwise} there when there is none.
	 */
	static void navigateAsync(Project project, String className, String methodName, Runnable otherwise) {
		ReadAction.nonBlocking(() -> target(project, className, methodName))
				.expireWith(project)
				.finishOnUiThread(ModalityState.defaultModalityState(), target -> {
					if (target instanceof Navigatable n && n.canNavigate()) {
						PsiNavigateUtil.navigate(target);
					} else {
						otherwise.run();
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	/** The method (or failing that the class) to go to; null when neither is found. Under a read action. */
	private static PsiElement target(Project project, String className, String methodName) {
		if (className == null || className.isEmpty()) {
			return null;
		}
		JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
		GlobalSearchScope scope = GlobalSearchScope.allScope(project);
		PsiClass psiClass = facade.findClass(className, scope);
		if (psiClass == null && className.indexOf('$') >= 0) {
			psiClass = facade.findClass(className.replace('$', '.'), scope);
		}
		if (psiClass == null) {
			return null;
		}
		if (methodName != null && !methodName.isEmpty()) {
			for (PsiMethod method : psiClass.findMethodsByName(methodName, true)) {
				if (method.canNavigate()) {
					return method;
				}
			}
		} else {
			PsiMethod sole = solePublicMethod(psiClass);
			if (sole != null && sole.canNavigate()) {
				return sole;
			}
		}
		return psiClass.canNavigate() ? psiClass : null;
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

/*
 * Copyright 2003-2015 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.threading;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ui.SingleCheckboxOptionsPanel;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.callMatcher.CallMatcher;
import com.siyeh.ig.psiutils.BlockUtils;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.function.Predicate;

public class WhileLoopSpinsOnFieldInspection extends BaseInspection {

  @SuppressWarnings({"PublicField"})
  public boolean ignoreNonEmtpyLoops = false;

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message(
      "while.loop.spins.on.field.display.name");
  }

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message(
      "while.loop.spins.on.field.problem.descriptor");
  }

  @Nullable
  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    return new SpinLoopFix((PsiField)infos[0], (boolean)infos[1]);
  }

  @Override
  @Nullable
  public JComponent createOptionsPanel() {
    return new SingleCheckboxOptionsPanel(InspectionGadgetsBundle.message(
      "while.loop.spins.on.field.ignore.non.empty.loops.option"),
                                          this, "ignoreNonEmtpyLoops");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new WhileLoopSpinsOnFieldVisitor();
  }

  private class WhileLoopSpinsOnFieldVisitor extends BaseInspectionVisitor {

    @Override
    public void visitWhileStatement(@NotNull PsiWhileStatement statement) {
      super.visitWhileStatement(statement);
      final PsiStatement body = statement.getBody();
      boolean empty = ControlFlowUtils.statementIsEmpty(body);
      if (ignoreNonEmtpyLoops && !empty) {
        return;
      }
      final PsiExpression condition = statement.getCondition();
      final PsiField field = getFieldIfSimpleFieldComparison(condition);
      if (field == null) {
        return;
      }
      boolean hasOnSpinWait = containsCall(body, CallMatcher.staticCall("java.lang.Thread", "onSpinWait"));
      boolean java9 = PsiUtil.isLanguageLevel9OrHigher(field);
      if (java9 && hasOnSpinWait && field.hasModifierProperty(PsiModifier.VOLATILE)) {
        return;
      }
      if (body != null && (VariableAccessUtils.variableIsAssigned(field, body) || containsCall(body, ThreadingUtils::isWaitCall))) {
        return;
      }
      registerStatementError(statement, field, java9 && !hasOnSpinWait);
    }

    private boolean containsCall(PsiElement element, Predicate<PsiMethodCallExpression> predicate) {
      final boolean[] result = new boolean[1];
      element.accept(new JavaRecursiveElementWalkingVisitor() {
        @Override
        public void visitMethodCallExpression(PsiMethodCallExpression expression) {
          super.visitMethodCallExpression(expression);
          if (predicate.test(expression)) {
            result[0] = true;
            stopWalking();
          }
        }
      });
      return result[0];
    }

    @Nullable
    private PsiField getFieldIfSimpleFieldComparison(PsiExpression condition) {
      condition = PsiUtil.deparenthesizeExpression(condition);
      if (condition == null) {
        return null;
      }
      final PsiField field = getFieldIfSimpleFieldAccess(condition);
      if (field != null) {
        return field;
      }
      if (condition instanceof PsiPrefixExpression) {
        final PsiPrefixExpression prefixExpression = (PsiPrefixExpression)condition;
        final PsiExpression operand = prefixExpression.getOperand();
        return getFieldIfSimpleFieldComparison(operand);
      }
      if (condition instanceof PsiPostfixExpression) {
        final PsiPostfixExpression postfixExpression = (PsiPostfixExpression)condition;
        final PsiExpression operand = postfixExpression.getOperand();
        return getFieldIfSimpleFieldComparison(operand);
      }
      if (condition instanceof PsiBinaryExpression) {
        final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)condition;
        final PsiExpression lOperand = binaryExpression.getLOperand();
        final PsiExpression rOperand = binaryExpression.getROperand();
        if (ExpressionUtils.isLiteral(rOperand)) {
          return getFieldIfSimpleFieldComparison(lOperand);
        }
        else if (ExpressionUtils.isLiteral(lOperand)) {
          return getFieldIfSimpleFieldComparison(rOperand);
        }
        else {
          return null;
        }
      }
      return null;
    }

    @Nullable
    private PsiField getFieldIfSimpleFieldAccess(PsiExpression expression) {
      expression = PsiUtil.deparenthesizeExpression(expression);
      if (expression == null) {
        return null;
      }
      if (!(expression instanceof PsiReferenceExpression)) {
        return null;
      }
      final PsiReferenceExpression reference = (PsiReferenceExpression)expression;
      final PsiExpression qualifierExpression = reference.getQualifierExpression();
      if (qualifierExpression != null) {
        return null;
      }
      final PsiElement referent = reference.resolve();
      if (!(referent instanceof PsiField)) {
        return null;
      }
      final PsiField field = (PsiField)referent;
      if (field.hasModifierProperty(PsiModifier.VOLATILE) && !PsiUtil.isLanguageLevel9OrHigher(field)) {
        return null;
      }
      else {
        return field;
      }
    }
  }

  private static class SpinLoopFix extends InspectionGadgetsFix {
    private final SmartPsiElementPointer<PsiField> myFieldPointer;
    private final String myFieldName;
    private final boolean myAddOnSpinWait;
    private final boolean myAddVolatile;

    public SpinLoopFix(PsiField field, boolean addOnSpinWait) {
      myFieldPointer = SmartPointerManager.getInstance(field.getProject()).createSmartPsiElementPointer(field);
      myFieldName = field.getName();
      myAddOnSpinWait = addOnSpinWait;
      myAddVolatile = !field.hasModifierProperty(PsiModifier.VOLATILE);
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      if(myAddOnSpinWait && myAddVolatile) {
        return "Declare field '" + myFieldName + "' as 'volatile' and add Thread.onSpinWait()";
      }
      if(myAddOnSpinWait) {
        return "Add Thread.onSpinWait()";
      }
      return "Declare field '" + myFieldName + "' as 'volatile'";
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Declare field as 'volatile'";
    }

    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor) {
      if(myAddVolatile) {
        addVolatile(myFieldPointer.getElement());
      }
      if(myAddOnSpinWait) {
        addOnSpinWait(descriptor.getStartElement());
      }
    }

    private static void addOnSpinWait(PsiElement element) {
      PsiLoopStatement loop = PsiTreeUtil.getParentOfType(element, PsiLoopStatement.class);
      if(loop == null) return;
      PsiStatement body = loop.getBody();
      if(body == null) return;
      PsiStatement spinCall =
        JavaPsiFacade.getElementFactory(element.getProject()).createStatementFromText("java.lang.Thread.onSpinWait();", element);
      if(body instanceof PsiBlockStatement) {
        PsiCodeBlock block = ((PsiBlockStatement)body).getCodeBlock();
        block.addAfter(spinCall, null);
      } else {
        BlockUtils.addBefore(body, spinCall);
      }
    }

    private static void addVolatile(PsiField field) {
      if (field == null) return;
      PsiModifierList list = field.getModifierList();
      if (list == null) return;
      list.setModifierProperty(PsiModifier.VOLATILE, true);
    }
  }
}
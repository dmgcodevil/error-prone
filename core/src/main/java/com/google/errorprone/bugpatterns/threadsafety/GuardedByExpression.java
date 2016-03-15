/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone.bugpatterns.threadsafety;

import static com.google.errorprone.bugpatterns.threadsafety.IllegalGuardedBy.checkGuardedBy;

import com.google.auto.value.AutoValue;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.Names;

import java.util.HashMap;
import java.util.Map;

import javax.lang.model.element.ElementKind;

/**
 * The lock expression of an {@code @GuardedBy} annotation.
 *
 * @author cushon@google.com (Liam Miller-Cushon)
 */
public abstract class GuardedByExpression {

  public abstract Kind kind();
  public abstract Symbol sym();
  public abstract Type type();
  
  static final String ENCLOSING_INSTANCE_NAME = "outer$";
  
  /**
   * A 'class' literal: ClassName.class
   */
  @AutoValue
  public abstract static class ClassLiteral extends GuardedByExpression {
    public static ClassLiteral create(Symbol owner) {
      return new AutoValue_GuardedByExpression_ClassLiteral(
          Kind.CLASS_LITERAL, owner, owner.type);
    }
  }

  /**
   * The base expression for a static member select on a class literal (e.g. ClassName.fieldName).
   */
  @AutoValue
  public abstract static class TypeLiteral extends GuardedByExpression {
    public static TypeLiteral create(Symbol owner) {
      return new AutoValue_GuardedByExpression_TypeLiteral(
          Kind.TYPE_LITERAL, owner, owner.type);
    }
  }

  /**
   * A local variable (or parameter), resolved as part of a lock access expression.
   */
  @AutoValue
  public abstract static class LocalVariable extends GuardedByExpression {
    public static LocalVariable create(Symbol owner) {
      return new AutoValue_GuardedByExpression_LocalVariable(
          Kind.LOCAL_VARIABLE, owner, owner.type);
    }
  }

  /**
   * A simple 'this literal.
   */
  // Don't use AutoValue here, since sym and type need to be 'null'. (And since
  // it's a singleton we don't need to implement equals() or hashCode()).
  public static class ThisLiteral extends GuardedByExpression {

    static final ThisLiteral INSTANCE = new ThisLiteral();

    @Override
    public Kind kind() {
      return Kind.THIS;
    }

    @Override
    public Symbol sym() {
      return null;
    }

    @Override
    public Type type() {
      return null;
    }

    private ThisLiteral() {}
  }

  /**
   * The member access expression for a field or method.
   */
  @AutoValue
  public abstract static class Select extends GuardedByExpression {
    
    public abstract GuardedByExpression base();

    public static Select create(GuardedByExpression base, Symbol sym, Type type) {
      return new AutoValue_GuardedByExpression_Select(Kind.SELECT, sym, type, base);
    }
  }

  /** Makes {@link GuardedByExpression}s. */
  public static class Factory {
    ThisLiteral thisliteral() {
      return ThisLiteral.INSTANCE;
    }

    private final Map<Symbol, VarSymbol> syntheticOuterFields = new HashMap<>();

    /**
     * Synthesizes the {@link GuardedByExpression} for an enclosing class access. The
     * access is represented as a chain of field accesses from an instance of the current class to
     * its enclosing ancestor. At each level, the enclosing class is accessed via a magic 'outer$'
     * field.
     * 
     * <p>Example:
     * 
     * <pre>
     * <code>
     * class Outer {
     *   final Object lock = new Object();
     *   class Middle {
     *     class Inner {
     *       @GuardedBy("lock") // resolves to 'this.outer$.outer$.lock'
     *       int x;
     *     }
     *   }
     * }
     * </code>
     * </pre>
     * 
     * @param  access    the inner class where the access occurs.
     * @param  enclosing the lexically enclosing class.
     */
    GuardedByExpression qualifiedThis(Names names, ClassSymbol access, Symbol enclosing) {
      GuardedByExpression base = thisliteral();
      Symbol curr = access;
      do {
        curr = curr.owner.enclClass();
        if (curr == null) {
          break;
        }
        VarSymbol var = syntheticOuterFields.get(curr);
        if (var == null) {
          var = new VarSymbol(
              Flags.SYNTHETIC, names.fromString(ENCLOSING_INSTANCE_NAME), curr.type, curr);
          syntheticOuterFields.put(curr, var);
        }   
        base = select(base, var);
      } while (!curr.equals(enclosing));
      checkGuardedBy(curr != null , "Expected an enclosing class.");
      return base;
    }

    ClassLiteral classLiteral(Symbol clazz) {
      return ClassLiteral.create(clazz);
    }

    TypeLiteral typeLiteral(Symbol type) {
      return TypeLiteral.create(type);
    }

    Select select(GuardedByExpression base, Symbol member) {
      if (member instanceof VarSymbol) {
        return select(base, (VarSymbol) member);
      }
      if (member instanceof MethodSymbol) {
        return select(base, (MethodSymbol) member);
      }
      throw new IllegalStateException(
          "Bad select expression: expected symbol " + member.getKind());
    }

    Select select(GuardedByExpression base, Symbol.VarSymbol member) {
      return Select.create(base, member, member.type);
    }

    Select select(GuardedByExpression base, Symbol.MethodSymbol member) {
      return Select.create(base, member, member.getReturnType());
    }

    GuardedByExpression select(GuardedByExpression base, Select select) {
      return Select.create(base, select.sym(), select.type());
    }

    LocalVariable localVariable(Symbol.VarSymbol varSymbol) {
      return LocalVariable.create(varSymbol);
    }
  }

  /** {@link GuardedByExpression} kind. */
  public static enum Kind {
    THIS, CLASS_LITERAL, TYPE_LITERAL, LOCAL_VARIABLE, SELECT;
  }

  @Override
  public String toString() {
    return PrettyPrinter.print(this);
  }

  public String debugPrint() {
    return DebugPrinter.print(this);
  }

  /**
   * Pretty printer for lock expressions.
   */
  private static class PrettyPrinter {

    public static String print(GuardedByExpression exp) {
      StringBuilder sb = new StringBuilder();
      pprint(exp, sb);
      return sb.toString();
    }

    private static void pprint(GuardedByExpression exp, StringBuilder sb) {
      switch (exp.kind()) {
        case CLASS_LITERAL:
          sb.append(String.format("%s.class", exp.sym().name));
          break;
        case THIS:
          sb.append("this");
          break;
        case TYPE_LITERAL:
        case LOCAL_VARIABLE:
          sb.append(exp.sym().name);
          break;
        case SELECT:
          pprintSelect((Select) exp, sb);
          break;
      }
    }

    private static void pprintSelect(Select exp, StringBuilder sb) {
      if (exp.sym().name.contentEquals(ENCLOSING_INSTANCE_NAME)) {
        GuardedByExpression curr = exp.base();
        while (curr.kind() == Kind.SELECT) {
          curr = ((Select) curr).base();
          if (curr.kind() == Kind.THIS) {
            break;
          }
        }
        if (curr.kind() == Kind.THIS) {
          sb.append(String.format("%s.this", exp.sym().owner.name));
        } else {
          pprint(exp.base(), sb);
          sb.append(".this$0");
        }
      } else {
        pprint(exp.base(), sb);
        sb.append(String.format(".%s", exp.sym().name));
        if (exp.sym().getKind() == ElementKind.METHOD) {
          sb.append("()");
        }
      }
    }
  }

  /**
   * s-exp pretty printer for lock expressions.
   */
  private static class DebugPrinter {
    public static String print(GuardedByExpression exp) {
      StringBuilder sb = new StringBuilder();
      pprint(exp, sb);
      return sb.toString();
    }

    private static void pprint(GuardedByExpression exp, StringBuilder sb) {
      switch (exp.kind()) {
        case TYPE_LITERAL:
        case CLASS_LITERAL:
        case LOCAL_VARIABLE:
          sb.append(String.format("(%s %s)", exp.kind(), exp.sym()));
          break;
        case THIS:
          sb.append("(THIS)");
          break;
        case SELECT:
          pprintSelect((Select) exp, sb);
          break;
      }
    }

    private static void pprintSelect(Select exp, StringBuilder sb) {
      sb.append(String.format("(%s ", exp.kind()));
      pprint(exp.base(), sb);
      if (exp.sym().name.contentEquals(ENCLOSING_INSTANCE_NAME)) {
        sb.append(String.format(" %s%s)", ENCLOSING_INSTANCE_NAME, exp.sym().owner));
      } else {
        sb.append(String.format(" %s)", exp.sym())); 
      }
    }
  }
}

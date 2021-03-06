package ewing.application.query;

import com.querydsl.core.Tuple;
import com.querydsl.core.group.GroupExpression;
import com.querydsl.core.types.*;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.SimpleExpression;
import com.querydsl.core.util.BeanUtils;
import com.querydsl.core.util.ReflectionUtils;
import com.querydsl.sql.PrimaryKey;
import com.querydsl.sql.RelationalPathBase;
import com.querydsl.sql.SQLQuery;
import org.springframework.util.Assert;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 查询帮助类。
 *
 * @author Ewing
 */
public class QueryUtils {

    private QueryUtils() {
    }

    /**
     * 使用分页参数和查询对象进行分页查询。
     */
    public static <T> Page<T> queryPage(Pager pager, SQLQuery<T> query) {
        // 是否统计总数
        if (pager.isCount()) {
            Page<T> page = new Page<>();
            page.setTotal(query.fetchCount());
            if (page.getTotal() < 1 || page.getTotal() < pager.getOffset()) {
                // 一条也没有或已经超出总数范围则返回空集
                return page.setRows(Collections.emptyList());
            } else {
                query.limit(pager.getLimit()).offset(pager.getOffset());
                return page.setRows(query.fetch());
            }
        } else {
            query.limit(pager.getLimit()).offset(pager.getOffset());
            return new Page<>(query.fetch());
        }
    }

    /**
     * 获取主键中的字段属性。
     */
    @SuppressWarnings("unchecked")
    public static List<Path> getKeyPaths(RelationalPathBase pathBase) {
        Assert.notNull(pathBase, "PathBase missing.");
        PrimaryKey primaryKey = pathBase.getPrimaryKey();
        Assert.notNull(primaryKey, "PrimaryKey missing.");
        return (List<Path>) primaryKey.getLocalColumns();
    }

    /**
     * 创建主键等于参数的表达式。
     */
    @SuppressWarnings("unchecked")
    public static BooleanExpression baseKeyEquals(RelationalPathBase pathBase, Object key) {
        List<Path> keyPaths = getKeyPaths(pathBase);
        Assert.notEmpty(keyPaths, "Key paths missing.");
        if (keyPaths.size() == 1) {
            return ((SimpleExpression) keyPaths.get(0)).eq(key);
        } else {
            // 多个主键时使用实体作为主键值创建表达式
            return beanKeyEquals(keyPaths, key);
        }
    }

    /**
     * 使用实体作为主键值创建表达式。
     */
    public static BooleanExpression beanKeyEquals(List<Path> keyPaths, Object bean) {
        Assert.notNull(bean, "Bean param missing.");
        Assert.notEmpty(keyPaths, "Key paths missing.");
        BooleanExpression equals = Expressions.FALSE;
        for (Path path : keyPaths) {
            String name = path.getMetadata().getName();
            Method getter = ReflectionUtils.getGetterOrNull(bean.getClass(), name);
            Assert.notNull(getter, "Key property getter missing.");
            try {
                BooleanExpression keyEqual = ((SimpleExpression) path).eq(getter.invoke(bean));
                equals = (equals == Expressions.FALSE ? keyEqual : equals.and(keyEqual));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        return equals;
    }

    /**
     * 获取Bean的属性Setter。
     */
    @SuppressWarnings("unchecked")
    public static Method findSetter(Object bean, String name, Class type) {
        Assert.notNull(bean, "Bean param missing.");
        String methodName = "set" + BeanUtils.capitalize(name);
        Class beanClass = bean.getClass();
        while (beanClass != null && !beanClass.equals(Object.class)) {
            try {
                return beanClass.getDeclaredMethod(methodName, type);
            } catch (SecurityException | NoSuchMethodException e) {
                // 跳过当前类并从父类查找
                beanClass = beanClass.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 给Bean设置属性值。
     */
    public static void setBeanProperty(Object bean, String name, Object value) {
        Assert.notNull(value, "Property value missing.");
        Method setter = findSetter(bean, name, value.getClass());
        Assert.notNull(setter, "Key setter missing.");
        try {
            setter.invoke(bean, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 使用与Bean属性匹配的Expression（包括实体查询对象）参数查询Bean。
     */
    public static <T> QBean<T> fitBean(Class<? extends T> type, Expression... expressions) {
        // 获取到Bean的所有属性
        PropertyDescriptor[] properties;
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(type);
            properties = beanInfo.getPropertyDescriptors();
        } catch (IntrospectionException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        // 获取参数中能够用的上的表达式
        Map<String, Expression<?>> expressionMap = new HashMap<>();
        for (Expression expression : expressions) {
            if (expression instanceof RelationalPathBase) {
                // 逐个匹配实体查询对象中的路径
                Expression[] paths = ((RelationalPathBase) expression).all();
                for (Expression path : paths) {
                    matchBindings(expressionMap, properties, path);
                }
            } else {
                // 匹配单个路径表达式是否用的上
                matchBindings(expressionMap, properties, expression);
            }
        }
        return Projections.bean(type, expressionMap);
    }

    /**
     * 根据属性匹配Expression并添加绑定到Map中。
     * 实现逻辑参考自QBean.createBindings方法。
     */
    private static void matchBindings(
            Map<String, Expression<?>> expressionMap,
            PropertyDescriptor[] properties, Expression expression) {
        if (expression instanceof Path<?>) {
            String name = ((Path<?>) expression).getMetadata().getName();
            for (PropertyDescriptor property : properties) {
                if (property.getName().equals(name) && property.getWriteMethod() != null) {
                    expressionMap.put(name, expression);
                    break; // 匹配到属性结束内层循环
                }
            }
        } else if (expression instanceof Operation<?>) {
            Operation<?> operation = (Operation<?>) expression;
            if (operation.getOperator() == Ops.ALIAS
                    && operation.getArg(1) instanceof Path<?>) {
                String name = ((Path<?>) operation.getArg(1)).getMetadata().getName();
                for (PropertyDescriptor property : properties) {
                    if (property.getName().equals(name) && property.getWriteMethod() != null) {
                        Expression<?> express = operation.getArg(0);
                        if (express instanceof FactoryExpression
                                || express instanceof GroupExpression) {
                            expressionMap.put(name, express);
                        } else {
                            expressionMap.put(name, operation);
                        }
                        break; // 匹配到属性结束内层循环
                    }
                }
            } else {
                throw new IllegalArgumentException("Unsupported expression " + expression);
            }
        } else {
            throw new IllegalArgumentException("Unsupported expression " + expression);
        }
    }

    /**
     * 一对多关联查询的结果列表转换成一对多的层级对象。
     * <p>
     * 例如把 List<Tuple> rows 转换成一对多的层级对象：
     * QueryUtils.oneToMany(
     * rows, qOne, qMany,
     * One::getOneKey,
     * Many::getManyKey,
     * One::getManies,
     * One::setManies))
     */
    public static <ONE, MANY, QONE extends Expression<ONE>, QMANY extends Expression<MANY>>
    List<ONE> oneToMany(List<Tuple> rows, QONE qOne, QMANY qMany,
                        Function<ONE, Serializable> oneKeyGetter,
                        Function<MANY, Serializable> manyKeyGetter,
                        Function<ONE, List<MANY>> manyGetter,
                        BiConsumer<ONE, List<MANY>> manySetter) {
        if (rows == null) {
            return null;
        }
        if (qOne == null || qMany == null
                || oneKeyGetter == null || manyKeyGetter == null
                || manyGetter == null || manySetter == null) {
            throw new IllegalArgumentException("Arguments missing.");
        }
        // 取一对多的“一”并根据“一”的Key去重
        Map<Serializable, ONE> oneMap = new HashMap<>();
        for (Tuple row : rows) {
            ONE one = row.get(qOne);
            if (one != null && oneKeyGetter.apply(one) != null) {
                ONE exists = oneMap.putIfAbsent(oneKeyGetter.apply(one), one);
                one = exists == null ? one : exists;
                // 取一对多的“多”并添加到“一”中
                MANY many = row.get(qMany);
                if (many != null && manyKeyGetter.apply(many) != null) {
                    if (manyGetter.apply(one) == null) {
                        manySetter.accept(one, new ArrayList<>());
                    }
                    manyGetter.apply(one).add(many);
                }
            }
        }
        return new ArrayList<>(oneMap.values());
    }

}

package com.github.database.rider.cdi;

import java.io.Serializable;
import java.lang.reflect.Method;

import javax.enterprise.inject.spi.CDI;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import javax.transaction.UserTransaction;

import com.github.database.rider.cdi.api.DBRider;
import com.github.database.rider.core.api.configuration.DataSetMergingStrategy;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import com.github.database.rider.cdi.api.DBUnitInterceptor;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.core.api.dataset.ExpectedDataSet;
import com.github.database.rider.core.api.leak.LeakHunter;
import com.github.database.rider.core.configuration.DBUnitConfig;
import com.github.database.rider.core.configuration.DataSetConfig;
import com.github.database.rider.core.leak.LeakHunterFactory;
import com.github.database.rider.core.util.AnnotationUtils;

/**
 * Created by pestano on 26/07/15.
 */
@Interceptor
@DBUnitInterceptor
public class DBUnitInterceptorImpl implements Serializable {

    @Inject
    DataSetProcessor dataSetProcessor;

    @AroundInvoke
    public Object intercept(InvocationContext invocationContext) throws Exception {

        Method method = invocationContext.getMethod();
        if (method.isAnnotationPresent(Before.class) || method.isAnnotationPresent(BeforeClass.class) || method.isAnnotationPresent(After.class) || method.isAnnotationPresent(AfterClass.class)) {
            return invocationContext.proceed();
        }
        Object proceed = null;
        DBUnitConfig dbUnitConfig = DBUnitConfig.from(invocationContext.getMethod());

        DataSet usingDataSet = resolveDataSet(invocationContext, dbUnitConfig);

        String entityManagerName = resolveEntityManagerName(invocationContext);
        dataSetProcessor.init(entityManagerName);
        if (usingDataSet != null) {
            DataSetConfig dataSetConfig = new DataSetConfig(usingDataSet.value()).cleanAfter(usingDataSet.cleanAfter())
                    .cleanBefore(usingDataSet.cleanBefore()).disableConstraints(usingDataSet.disableConstraints())
                    .executeScripsBefore(usingDataSet.executeScriptsBefore())
                    .executeScriptsAfter(usingDataSet.executeScriptsAfter())
                    .executeStatementsAfter(usingDataSet.executeStatementsAfter())
                    .executeStatementsBefore(usingDataSet.executeStatementsBefore()).strategy(usingDataSet.strategy())
                    .transactional(usingDataSet.transactional()).tableOrdering(usingDataSet.tableOrdering())
                    .datasetProvider(usingDataSet.provider())
                    .skipCleaningFor(usingDataSet.skipCleaningFor())
                    .replacers(usingDataSet.replacers())
                    .useSequenceFiltering(usingDataSet.useSequenceFiltering());
            dataSetProcessor.process(dataSetConfig, dbUnitConfig);
            boolean isTransactionalTest = dataSetConfig.isTransactional();
            if (isTransactionalTest) {
                if (dataSetProcessor.isJta()) {
                    CDI.current().select(UserTransaction.class).get().begin();
                } else {
                    dataSetProcessor.getEntityManager().getTransaction().begin();
                }
            }
            LeakHunter leakHunter = null;
            try {
                if (dbUnitConfig.isLeakHunter()) {
                    leakHunter = LeakHunterFactory.from(dataSetProcessor.getDataSetExecutor().getRiderDataSource(),
                            invocationContext.getMethod().getName());
                    leakHunter.measureConnectionsBeforeExecution();
                }
                proceed = invocationContext.proceed();

                if (isTransactionalTest) {
                    if (dataSetProcessor.isJta()) {
                        CDI.current().select(UserTransaction.class).get().commit();
                    } else {
                        dataSetProcessor.getEntityManager().getTransaction().commit();
                    }
                }
                ExpectedDataSet expectedDataSet = invocationContext.getMethod().getAnnotation(ExpectedDataSet.class);
                if (expectedDataSet != null) {
                    dataSetProcessor.compareCurrentDataSetWith(
                            new DataSetConfig(expectedDataSet.value())
                                    .datasetProvider(expectedDataSet.provider())
                                    .disableConstraints(true),
                            expectedDataSet.ignoreCols());
                }
            } finally {
                if (isTransactionalTest) {
                    if (dataSetProcessor.isJta()) {
                        CDI.current().select(UserTransaction.class).get().rollback();
                    } else if (dataSetProcessor.getEntityManager().getTransaction().isActive()) {
                        dataSetProcessor.getEntityManager().getTransaction().rollback();
                    }
                }


                dataSetProcessor.exportDataSet(invocationContext.getMethod());

                if (usingDataSet.executeStatementsAfter().length > 0) {
                    dataSetProcessor.executeStatements(dataSetConfig.getExecuteStatementsAfter());
                }

                if (usingDataSet.executeScriptsAfter().length > 0) {
                    for (int i = 0; i < usingDataSet.executeScriptsAfter().length; i++) {
                        dataSetProcessor.executeScript(usingDataSet.executeScriptsAfter()[i]);
                    }
                }

                if (usingDataSet.cleanAfter()) {
                    dataSetProcessor.clearDatabase(dataSetConfig);
                }

                dataSetProcessor.enableConstraints();
                dataSetProcessor.getEntityManager().clear();
                if (leakHunter != null) {
                    leakHunter.checkConnectionsAfterExecution();
                }
                dataSetProcessor.afterTest(entityManagerName);
            } // end finally

        } else {// no dataset provided, just proceed and check expectedDataSet
            try {
                proceed = invocationContext.proceed();
                ExpectedDataSet expectedDataSet = invocationContext.getMethod().getAnnotation(ExpectedDataSet.class);
                if (expectedDataSet != null) {
                    dataSetProcessor.compareCurrentDataSetWith(
                            new DataSetConfig(expectedDataSet.value()).disableConstraints(true),
                            expectedDataSet.ignoreCols());
                }
            } finally {
                dataSetProcessor.exportDataSet(invocationContext.getMethod());
                dataSetProcessor.afterTest(entityManagerName);
            }

        }

        return proceed;
    }

    private String resolveEntityManagerName(InvocationContext invocationContext) {
        String entityManagerName = "";
        DBRider dbRiderClassLevel = AnnotationUtils.findAnnotation(invocationContext.getMethod().getDeclaringClass(), DBRider.class);
        DBRider dbRiderMethodLevel = AnnotationUtils.findAnnotation(invocationContext.getMethod(), DBRider.class);
        DBUnitInterceptor dbunitInterceptorClassLevel = AnnotationUtils.findAnnotation(invocationContext.getMethod().getDeclaringClass(), DBUnitInterceptor.class);
        DBUnitInterceptor dbunitInterceptorMethodLevel = AnnotationUtils.findAnnotation(invocationContext.getMethod(), DBUnitInterceptor.class);
        if (dbRiderMethodLevel != null) {
            entityManagerName = dbRiderMethodLevel.entityManagerName();
        } else if (dbRiderClassLevel != null) {
            entityManagerName = dbRiderClassLevel.entityManagerName();
        } else if (dbunitInterceptorMethodLevel != null) {
            entityManagerName = dbunitInterceptorMethodLevel.entityManagerName();
        } else if (dbunitInterceptorClassLevel != null) {
            entityManagerName = dbunitInterceptorClassLevel.entityManagerName();
        }
        return entityManagerName;
    }

    private DataSet resolveDataSet(InvocationContext invocationContext, DBUnitConfig config) {
        DataSet methodAnnotation = AnnotationUtils.findAnnotation(invocationContext.getMethod(), DataSet.class);
        DataSet classAnnotation = AnnotationUtils.findAnnotation(invocationContext.getMethod().getDeclaringClass(),
                DataSet.class);

        if (config.isMergeDataSets() && (classAnnotation != null && methodAnnotation != null)) {
            if(DataSetMergingStrategy.METHOD.equals(config.getDataSetMergingStrategy())) {
                return AnnotationUtils.mergeDataSetAnnotations(classAnnotation, methodAnnotation);
            } else {
                return AnnotationUtils.mergeDataSetAnnotations(methodAnnotation, classAnnotation);
            }
        }

        if (methodAnnotation != null) {
            return methodAnnotation;
        }

        return classAnnotation;

    }

}

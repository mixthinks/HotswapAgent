package org.hotswap.agent.plugin.hibernate;

import org.hotswap.agent.annotation.Init;
import org.hotswap.agent.annotation.Plugin;
import org.hotswap.agent.annotation.Transform;
import org.hotswap.agent.annotation.Watch;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.command.ReflectionCommand;
import org.hotswap.agent.command.Scheduler;
import org.hotswap.agent.javassist.ClassPool;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.CtMethod;
import org.hotswap.agent.javassist.CtNewMethod;
import org.hotswap.agent.javassist.bytecode.AccessFlag;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.util.AnnotationHelper;
import org.hotswap.agent.watch.WatchEvent;

/**
 * Reload Hibernate configuration after entity create/change.
 *
 * @author Jiri Bubnik
 */
@Plugin(name = "Hibernate", description = "Reload Hibernate configuration after entity create/change.",
        testedVersions = {"4.1.7"},
        expectedVersions = {"4x"})
public class HibernatePlugin {
    private static AgentLogger LOGGER = AgentLogger.getLogger(HibernatePlugin.class);
    private static final String ENTITY_ANNOTATION = "javax.persistence.Entity";

    @Init
    Scheduler scheduler;

    // refresh commands
    Command reloadEntityManagerFactoryCommand =
            new ReflectionCommand(this, HibernateRefreshCommands.class.getName(), "reloadEntityManagerFactory");
    Command reloadSessionFactoryCommand =
            new ReflectionCommand(this, HibernateRefreshCommands.class.getName(), "reloadSessionFactory");

    // is EJB3 or plain hibernate
    boolean hibernateEjb;


    /**
     * Plugin initialization properties (from HibernatePersistenceHelper or SessionFactoryWrapper)
     */
    public void hibernateInitialized(String version, Boolean hibernateEjb) {
        LOGGER.debug("Hibernate plugin version initialized for Hibernate Core '{}'", version);
        this.hibernateEjb = hibernateEjb;
    }


    /**
     * Override HibernatePersistence.createContainerEntityManagerFactory() to return EntityManagerFactory proxy object.
     * {@link org.hotswap.agent.plugin.hibernate.EntityManagerFactoryWrapper} holds reference to all proxied factories
     * and on refresh command replaces internal factory with fresh instance.
     * <p/>
     * Two variants covered - createContainerEntityManagerFactory and createEntityManagerFactory.
     * <p/>
     * After the entity manager factory and it's proxy are instantiated, plugin hibernateInitialized method is invoked.
     */
    @Transform(classNameRegexp = "org.hibernate.ejb.HibernatePersistence")
    public static void proxyHibernatePersistence(ClassLoader classLoader, CtClass clazz) throws Exception {
        LOGGER.debug("Override org.hibernate.ejb.HibernatePersistence#createContainerEntityManagerFactory and createEntityManagerFactory to create a EntityManagerFactoryWrapper proxy.");

        CtMethod oldMethod = clazz.getDeclaredMethod("createContainerEntityManagerFactory");
        oldMethod.setName("_createContainerEntityManagerFactory");
        CtMethod newMethod = CtNewMethod.make(
                "public javax.persistence.EntityManagerFactory createContainerEntityManagerFactory(" +
                        "           javax.persistence.spi.PersistenceUnitInfo info, java.util.Map properties) {" +
                        "  return " + HibernatePersistenceHelper.class.getName() + ".createContainerEntityManagerFactoryProxy(" +
                        "      info, properties, _createContainerEntityManagerFactory(info, properties)); " +
                        "}", clazz);
        clazz.addMethod(newMethod);

        oldMethod = clazz.getDeclaredMethod("createEntityManagerFactory");
        oldMethod.setName("_createEntityManagerFactory");

        newMethod = CtNewMethod.make(
                "public javax.persistence.EntityManagerFactory createEntityManagerFactory(" +
                        "           String persistenceUnitName, java.util.Map properties) {" +
                        "  return " + HibernatePersistenceHelper.class.getName() + ".createEntityManagerFactoryProxy(" +
                        "      persistenceUnitName, properties, _createEntityManagerFactory(persistenceUnitName, properties)); " +
                        "}", clazz);
        clazz.addMethod(newMethod);
    }

    /**
     * Remove final flag from SessionFactoryImpl - we need to create a proxy on session factory and cannot
     * use SessionFactory interface, because hibernate makes type cast to impl.
     */
    @Transform(classNameRegexp = "org.hibernate.internal.SessionFactoryImpl")
    public static void removeSessionFactoryImplFinalFlag(CtClass clazz) throws Exception {
        clazz.getClassFile().setAccessFlags(AccessFlag.PUBLIC);
    }

    @Transform(classNameRegexp = "org.hibernate.cfg.Configuration")
    public static void proxySessionFactory(ClassLoader classLoader, ClassPool classPool, CtClass clazz) throws Exception {
        // proceed only if EJB not available by the classloader
        if (checkHibernateEjb(classLoader))
            return;

        LOGGER.debug("Override org.hibernate.cfg.Configuration#buildSessionFactory to create a SessionFactoryWrapper proxy.");

        CtClass serviceRegistryClass = classPool.makeClass("org.hibernate.service.ServiceRegistry");
        CtMethod oldMethod = clazz.getDeclaredMethod("buildSessionFactory", new CtClass[]{serviceRegistryClass});
        oldMethod.setName("_buildSessionFactory");

        CtMethod newMethod = CtNewMethod.make(
                "public org.hibernate.SessionFactory buildSessionFactory(org.hibernate.service.ServiceRegistry serviceRegistry) throws org.hibernate.HibernateException {" +
                        "  return " + SessionFactoryWrapper.class.getName() + ".getWrapper(this)" +
                        "       .proxy(_buildSessionFactory(serviceRegistry), serviceRegistry); " +
                        "}", clazz);
        clazz.addMethod(newMethod);
    }

    /**
     * Reload after entity class change. It covers also @Entity annotation removal.
     */
    @Transform(classNameRegexp = ".*", onDefine = false)
    public void entityReload(CtClass clazz, Class original) {
        // TODO list of entity/resource files is known to hibernate, better to check this list
        if (AnnotationHelper.hasAnnotation(original, ENTITY_ANNOTATION)
            || AnnotationHelper.hasAnnotation(clazz, ENTITY_ANNOTATION)
                ) {
            LOGGER.debug("Entity reload class {}, original classloader {}", clazz.getName(), original.getClassLoader());
            refresh();
        }
    }

    /**
     * New entity class - not covered by reloading mechanism.
     */
    @Watch(path = ".", filter = ".*.class", watchEvents = {WatchEvent.WatchEventType.CREATE})
    public void newEntity(CtClass clazz) throws Exception {
        // FIXME
//        if (AnnotationHelper.hasAnnotation(clazz, ENTITY_ANNOTATION)) {
//            refresh();
//        }
    }

    private void refresh() {
        LOGGER.debug("Refreshing hibernate configuration.");
        if (hibernateEjb)
            scheduler.scheduleCommand(reloadEntityManagerFactoryCommand);
        else
            scheduler.scheduleCommand(reloadSessionFactoryCommand);
    }

    // check if plain Hibernate or EJB mode.
    private static boolean checkHibernateEjb(ClassLoader classLoader) {
        try {
            classLoader.loadClass("org.hibernate.ejb.HibernatePersistence");
            return true;
        } catch (ClassNotFoundException e) {
        }
        return false;
    }

}


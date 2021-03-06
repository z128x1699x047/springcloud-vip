package com.xiangxue.jack.refresh.curator;

import com.xiangxue.jack.refresh.scope.RefreshScopeRegistry;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.*;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

//@Component
public class CuratorUtil implements ApplicationContextAware {

    private static String connnectStr = "192.168.67.139:2181";

    private static CuratorFramework client;

    private static String path = "/config";

    @Value(("${zookeeper.config.enable:false}"))
    private boolean enbale;

    @Autowired
    Environment environment;

    private static String zkPropertyName = "zookeeperSource";

    private static String scopeName = "refresh";

    private static ConfigurableApplicationContext applicationContext;

    private ConcurrentHashMap map = new ConcurrentHashMap();

    private BeanDefinitionRegistry beanDefinitionRegistry;

    @PostConstruct
    public void init() {
        if (!enbale) return;

        RefreshScopeRegistry refreshScopeRegistry = (RefreshScopeRegistry)applicationContext.getBean("refreshScopeRegistry");
        beanDefinitionRegistry = refreshScopeRegistry.getBeanDefinitionRegistry();

        client = CuratorFrameworkFactory.
                builder().
                connectString(connnectStr).
                sessionTimeoutMs(5000).
                retryPolicy(new ExponentialBackoffRetry(1000, 3)).
                build();

        client.start();
        try {
            Stat stat = client.checkExists().forPath(path);
            if (stat == null) {
                client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).
                        forPath(path, "zookeeper config".getBytes());
                TimeUnit.SECONDS.sleep(1);
            } else {
                //1??????config???????????????????????????spring????????????????????????
                addChildToSpringProperty(client, path);
            }

//            nodeCache(client,path);
            childNodeCache(client, path);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addChildToSpringProperty(CuratorFramework client, String path) {
        if (!checkExistsSpringProperty()) {
            //???????????????zookeeper??????????????????????????????
            createZookeeperSpringProperty();
        }

        //???config?????????????????????????????? zk???PropertySource?????????
        MutablePropertySources propertySources = applicationContext.getEnvironment().getPropertySources();
        PropertySource<?> propertySource = propertySources.get(zkPropertyName);
        ConcurrentHashMap zkmap = (ConcurrentHashMap) propertySource.getSource();
        try {
            List<String> strings = client.getChildren().forPath(path);
            for (String string : strings) {
                zkmap.put(string, client.getData().forPath(path + "/" + string));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createZookeeperSpringProperty() {
        MutablePropertySources propertySources = applicationContext.getEnvironment().getPropertySources();
        OriginTrackedMapPropertySource zookeeperSource = new OriginTrackedMapPropertySource(zkPropertyName, map);
        propertySources.addLast(zookeeperSource);
    }

    private boolean checkExistsSpringProperty() {
        MutablePropertySources propertySources = applicationContext.getEnvironment().getPropertySources();
        for (PropertySource<?> propertySource : propertySources) {
            if (zkPropertyName.equals(propertySource.getName())) {
                return true;
            }
        }
        return false;
    }

    private void childNodeCache(CuratorFramework client, String path) {
        try {
            final PathChildrenCache pathChildrenCache = new PathChildrenCache(client, path, false);
            pathChildrenCache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);

            pathChildrenCache.getListenable().addListener(new PathChildrenCacheListener() {
                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                    switch (event.getType()) {
                        case CHILD_ADDED:
                            System.out.println("???????????????");
                            addEnv(event.getData(), client);
                            break;
                        case CHILD_REMOVED:
                            System.out.println("???????????????");
                            delEnv(event.getData());
                            break;
                        case CHILD_UPDATED:
                            System.out.println("???????????????");
                            addEnv(event.getData(), client);
                            break;
                        default:
                            break;
                    }
                    //???refresh??????????????????????????????
                    refreshBean();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void refreshBean() {
        String[] beanDefinitionNames = applicationContext.getBeanDefinitionNames();
        for (String beanDefinitionName : beanDefinitionNames) {
            BeanDefinition beanDefinition = beanDefinitionRegistry.getBeanDefinition(beanDefinitionName);
            if(scopeName.equals(beanDefinition.getScope())) {
                //?????????,,,,?????????????????????????????????bean?????????????????????
                applicationContext.getBeanFactory().destroyScopedBean(beanDefinitionName);
                //????????????
                applicationContext.getBean(beanDefinitionName);
            }
        }
    }

    private void delEnv(ChildData childData) {
        ChildData next = childData;
        String childpath = next.getPath();
        MutablePropertySources propertySources = applicationContext.getEnvironment().getPropertySources();
        for (PropertySource<?> propertySource : propertySources) {
            if (zkPropertyName.equals(propertySource.getName())) {
                OriginTrackedMapPropertySource ps = (OriginTrackedMapPropertySource) propertySource;
                ConcurrentHashMap chm = (ConcurrentHashMap) ps.getSource();
                chm.remove(childpath.substring(path.length() + 1));
            }
        }
    }

    private void addEnv(ChildData childData, CuratorFramework client) {
        ChildData next = childData;
        String childpath = next.getPath();
        String data = null;
        try {
            data = new String(client.getData().forPath(childpath));
        } catch (Exception e) {
            e.printStackTrace();
        }
        MutablePropertySources propertySources = applicationContext.getEnvironment().getPropertySources();
        for (PropertySource<?> propertySource : propertySources) {
            if (zkPropertyName.equals(propertySource.getName())) {
                OriginTrackedMapPropertySource ps = (OriginTrackedMapPropertySource) propertySource;
                ConcurrentHashMap chm = (ConcurrentHashMap) ps.getSource();
                chm.put(childpath.substring(path.length() + 1), data);
            }
        }
    }

    private void nodeCache(final CuratorFramework client, final String path) {

        try {
            //??????????????????????????????
            //?????????path??????????????????????????????????????????
            final NodeCache nodeCache = new NodeCache(client, path, false);
            nodeCache.start();

            //????????????????????????
            nodeCache.getListenable().addListener(new NodeCacheListener() {
                @Override
                public void nodeChanged() throws Exception {
                    byte[] data = nodeCache.getCurrentData().getData();
                    String path1 = nodeCache.getCurrentData().getPath();

                    Object put = map.put(path1.replace("/", ""), new String(data));
                    MutablePropertySources propertySources = applicationContext.getEnvironment().getPropertySources();
                    OriginTrackedMapPropertySource zookeeperSource = new OriginTrackedMapPropertySource("zookeeper source", map);
                    propertySources.addLast(zookeeperSource);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        CuratorUtil.applicationContext = (ConfigurableApplicationContext) context;
    }
}

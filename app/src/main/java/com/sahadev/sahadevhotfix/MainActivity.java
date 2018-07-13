package com.sahadev.sahadevhotfix;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.widget.TextView;

import com.sahadev.bean.ClassStudent;
import com.tbruyelle.rxpermissions.RxPermissions;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;
import rx.functions.Action1;

public class MainActivity extends Activity {

    private TextView mSampleText;

    private final boolean dexLoadTest = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        mSampleText = (TextView) findViewById(R.id.sample_text);

        //打印加载当前Activity的ClassLoader
        printClassLoader();


        RxPermissions rxPermissions = new RxPermissions(this);
        rxPermissions.request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .subscribe(new Action1<Boolean>() {
                    @Override
                    public void call(Boolean granted) {
                        if (granted) {
                            String unzipRAWFile = unzipRAWFile(MainActivity.this); //  拷贝到  /Android/{包名}/cache目录下
//                            loadClass(unzipRAWFile); //  加载指定路径的dex
                            inject(unzipRAWFile);
                            demonstrationRawMode();
                        }
                    }
                });


    }

    private void printClassLoader() {
        /**
         * dalvik.system.PathClassLoader[DexPathList[[zip file "/system/framework/org.apache.http.legacy.boot.jar", zip file "/data/app/com.sahadev.sahadevhotfix-afXImZFOQlpkczRlDocfvw==/base.apk"],nativeLibraryDirectories=[/data/app/com.sahadev.sahadevhotfix-afXImZFOQlpkczRlDocfvw==/lib/x86, /system/lib]]]
         * java.lang.BootClassLoader@76afc70
         */
        ClassLoader classLoader = getClassLoader();
        while (null != classLoader) {
            System.out.println(classLoader);
            classLoader = classLoader.getParent();
        }
    }

    /**
     * 验证替换类后的效果
     */
    private void demonstrationRawMode() {
        ClassStudent classStudent = new ClassStudent();
        classStudent.setName("Lavon");
        System.out.println(classStudent.getName());
    }

    /**
     * 这段代码的核心在于将DexClassLoader中的dexElements与PathClassLoader中的dexElements进行合并，
     * 然后将合并后的dexElements替换原先的dexElements。
     * 最后我们在使用ClassStudent类的时候便可以直接使用外部的ClassStudent，而不会再加载默认的ClassStudent类。
     */
    public String inject(String apkPath) {

        File optimizedDirectory = getOptimizedDirectory(apkPath);
        boolean hasBaseDexClassLoader = true;
        try {
            Class.forName("dalvik.system.BaseDexClassLoader");
        } catch (ClassNotFoundException e) {
            hasBaseDexClassLoader = false;
        }

        if (hasBaseDexClassLoader) {
            PathClassLoader pathClassLoader = (PathClassLoader) getClassLoader();//获取加载当前类的ClassLoader
            DexClassLoader dexClassLoader = new DexClassLoader(apkPath, optimizedDirectory.getAbsolutePath(), apkPath, pathClassLoader);
            try {

                //获取当前classLoader(PathClassLoader)的dexElements,默认一个数组中都只有1个dexFile
                Object[] dexElements1 = (Object[]) getDexElements(getPathList(pathClassLoader));
                System.out.println("dexElements1=" + Arrays.toString(dexElements1) + ",len=" + dexElements1.length);
                //获取DexClassLoader中的dexElements
                Object[] dexElements2 = (Object[]) getDexElements(getPathList(dexClassLoader));
                System.out.println("dexElements2=" + Arrays.toString(dexElements2) + ",len=" + dexElements2.length);
                /**
                 *  合并dexElements
                 *  1、热修复：将代表外部dex的dexElement放在了第0个位置
                 *  2、dex分包：直接将外部dex的dexElements放在同一个数组中即可。
                 */
                Object dexElements = combineArray(dexElements1, dexElements2);
                System.out.println("合并后的dexElements=" + Arrays.toString((Object[]) dexElements));
                Object pathList = getPathList(pathClassLoader);
                //将原先的dexElements
                setField(pathList, pathList.getClass(), "dexElements", dexElements);
                return "SUCCESS";
            } catch (Throwable e) {
                e.printStackTrace();
                return android.util.Log.getStackTraceString(e);
            }
        }
        return "SUCCESS";
    }

    private File getOptimizedDirectory(String apkPath) {
        return getDir("dex", 0);
    }

    public void setField(Object pathList, Class aClass, String fieldName, Object fieldValue) {

        try {
            Field declaredField = aClass.getDeclaredField(fieldName);
            declaredField.setAccessible(true);
            declaredField.set(pathList, fieldValue);

        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

    }

    //两个数组对象合并成为一个
    public Object combineArray(Object object, Object object2) {
        Class<?> aClass = Array.get(object, 0).getClass();

        Object obj = Array.newInstance(aClass, 2);

        Array.set(obj, 0, Array.get(object2, 0));
        Array.set(obj, 1, Array.get(object, 0));

        return obj;
    }

    public Object getDexElements(Object object) {
        if (object == null)
            return null;

        Class<?> aClass = object.getClass();
        try {
            Field dexElements = aClass.getDeclaredField("dexElements");
            dexElements.setAccessible(true);
            return dexElements.get(object);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;

    }


    //获取PathList，只有BaseDexClassLoader类中才用pathList变量
    public Object getPathList(BaseDexClassLoader classLoader) {
        Class<? extends BaseDexClassLoader> aClass = classLoader.getClass();

        Class<?> superclass = aClass.getSuperclass();
        try {

            Field pathListField = superclass.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object object = pathListField.get(classLoader);

            return object;
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 加载指定路径的dex
     *
     * @param apkPath
     */
    private void loadClass(String apkPath) {
        //该方法内的以下代码需要注释
        if (!dexLoadTest) {
            return;
        }

        /**
         *  ------PathClassLoader
         *          ---BootClassLoader
         */

        ClassLoader classLoader = getClassLoader();
        while (null != classLoader) {
            System.out.println(classLoader);
            classLoader = classLoader.getParent();
        }

        classLoader = getClassLoader();
        File file = new File(apkPath);

        //将优化后的dex放在 optimizedDirectory目录下
        File optimizedDirectoryFile = new File(file.getParentFile(), "optimizedDirectory");

        if (!optimizedDirectoryFile.exists())
            optimizedDirectoryFile.mkdir();

        try {

            DexClassLoader dexClassLoader = new DexClassLoader(apkPath, optimizedDirectoryFile.getAbsolutePath(), "", classLoader);
            System.out.println("-----------------");
            classLoader = dexClassLoader;
            while (null != classLoader) {
                System.out.println(classLoader);
                classLoader = classLoader.getParent();
            }
            /**
             * 类的加载是从上而下加载的，所以就算是DexClassLoader加载了外部的类，
             * 但是在系统使用类的时候还是会先在ClassLoader中查找，如果找不到则会在BaseDexClassLoader中查找，
             * 如果再找不到，就会进入PathClassLoader中查找，最后才会使用DexClassLoader进行查找，
             * 所以按照这个流程外部类是无法正常发挥作用的。所以我们的目的就是在查找工程内的类之前，先让加载器去外部的dex中查找
             */
            Class<?> aClass = dexClassLoader.loadClass("com.sahadev.bean.ClassStudent");
            System.out.println("com.sahadev.bean.ClassStudent = " + aClass);

            Object instance = aClass.newInstance();
            Method method = aClass.getMethod("setName", String.class);
            method.invoke(instance, "Sahadev");

            Method getNameMethod = aClass.getMethod("getName");
            Object invoke = getNameMethod.invoke(instance);//Sahadev.Miss 而不是Sahadev.Mr  加载的还是默认的ClassStudent，而不是外部的ClassStudent

            System.out.println("invoke result = " + invoke);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 解压原始的dex文件
     *
     * @param context
     * @return
     * @throws IOException
     */
    private String unzipRAWFile(Context context) {

        Resources resources = context.getResources();
        File externalCacheDir = context.getExternalCacheDir();

        //拷贝到/Android/{包名}/cache目录下
        File file = new File(externalCacheDir, resources.getResourceEntryName(R.raw.user) + ".dex");
        if (!file.exists()) {
            String apkFilePath = file.getAbsolutePath();
            InputStream inputStream = null;
            BufferedOutputStream bufferedOutputStream = null;
            try {
                inputStream = resources.openRawResource(R.raw.user);
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                bufferedOutputStream = new BufferedOutputStream(fileOutputStream);

                byte[] buffer = new byte[4 * 1024];
                int size;

                while ((size = inputStream.read(buffer)) != -1) {
                    bufferedOutputStream.write(buffer, 0, size);
                    bufferedOutputStream.flush();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    if (inputStream != null)
                        inputStream.close();
                    if (bufferedOutputStream != null)
                        bufferedOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            System.out.println("文件解压完毕，路径地址为：" + apkFilePath);
        } else {
            System.out.println("文件已存在，无需解压");
        }

        return file.getAbsolutePath();
    }

}

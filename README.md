# cldr-slice

将 CLDR 农历数据裁剪为 UTF-8 properties，包含历法名称、月份及极简名称、
闰月模板、二十四节气、六十干支和十二生肖。

## 运行

需要 JDK 25 和 Maven。将 CLDR 48.2 解压到 `cldr\cldr-common-48.2`，
在项目根目录运行，或在 IDEA 中运行 `io.github.sbracely.Main`：

```powershell
mvn compile
java -cp .\target\classes io.github.sbracely.Main
```

可通过 `--cldr-dir <common目录>` 和 `--output <输出目录>` 指定路径。

## 输出与使用

默认输出到 `properties`：`chinese-calendar.properties` 及所有语言文件。
语言文件包含差异和必要的继承覆盖值；空文件用于阻止 JVM 默认语言回退。

将文件放入应用 classpath：

```java
ResourceBundle bundle = ResourceBundle.getBundle("chinese-calendar", Locale.CHINA);
String month = bundle.getString("month.1");
```

属性：`name`、`month.1`～`12`、`month.narrow.1`～`12`、
`month.leapPattern`、`solarTerm.1`～`24`、`sexagenary.1`～`60`、
`zodiac.1`～`12`。

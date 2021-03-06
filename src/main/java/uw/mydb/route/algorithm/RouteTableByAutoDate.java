package uw.mydb.route.algorithm;

import uw.mydb.conf.MydbConfig;
import uw.mydb.route.RouteAlgorithm;
import uw.mydb.route.SchemaCheckService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 根据给定的日期，给出归属表名，支持动态自动建表。
 * 参数：
 * date-pattern: 可以不指定，设置为日期来源格式
 * format-pattern：格式化成的样式
 *
 * @author axeon
 */
public class RouteTableByAutoDate extends RouteAlgorithm {

    /**
     * 日期数据格式。
     */
    private static final DateTimeFormatter DATE_PATTERN_DEFAULT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 日期数据格式。
     */
    private DateTimeFormatter DATE_PATTERN = null;
    /**
     * 格式化的格式。
     */
    private DateTimeFormatter FORMAT_PATTERN = null;
    /**
     * 格式化的样式字符串。
     */
    private String FORMAT_PATTERN_CODE = null;

    /**
     * 日期预创建数量。
     */
    private int prepareNum = 1;


    @Override
    public void config() {
        Map<String, String> params = algorithmConfig.getParams();
        //提前创建的日期分表数量。
        String prepareNumStr = params.getOrDefault("prepare-num", "1");
        try {
            prepareNum = Integer.parseInt(prepareNumStr);
        } catch (Exception e) {
        }
        //datePattern,日期时间格式
        String datePattern = params.get("date-pattern");
        if (datePattern != null) {
            DATE_PATTERN = DateTimeFormatter.ofPattern(datePattern);
        }
        //formatPattern,格式化的日期时间格式
        String formatPattern = params.get("format-pattern");
        if (formatPattern != null) {
            FORMAT_PATTERN = DateTimeFormatter.ofPattern(formatPattern);
            if ("yyyyMMdd".equals(formatPattern) || "yyyyMM".equals(formatPattern) || "yyyy".equals(formatPattern) || "yyMMdd".equals(formatPattern) || "yyMM".equals(formatPattern) || "yy".equals(formatPattern) || "MMdd".equals(formatPattern) || "MM".equals(formatPattern) || "dd".equals(formatPattern)) {
                FORMAT_PATTERN_CODE = formatPattern;
            }
        }
    }

    @Override
    public RouteInfo calculate(MydbConfig.TableConfig tableConfig, RouteInfo routeInfo, String value) throws RouteException {
        String text = null;
        //优先选择快速格式化
        if (DATE_PATTERN == null && FORMAT_PATTERN_CODE != null) {
            text = quickFormat(routeInfo.getTable(), value, FORMAT_PATTERN_CODE);
        }
        if (text == null) {
            if (DATE_PATTERN != null) {
                LocalDateTime date = LocalDateTime.parse(value, DATE_PATTERN);
                if (FORMAT_PATTERN != null) {
                    text = date.format(FORMAT_PATTERN);
                }
            }
        }
        routeInfo.setTable(text);
        return routeInfo;
    }

    @Override
    public List<RouteInfo> calculateRange(MydbConfig.TableConfig tableConfig, List<RouteInfo> routeInfos, String startValue, String endValue) throws RouteException {
        if (startValue == null) {
            startValue = endValue;
        }
        if (endValue == null) {
            endValue = startValue;
        }
        if (startValue == null && endValue == null) {
            return routeInfos;
        }
        List<String> list = new ArrayList<>();
        LocalDateTime startDate, endDate;
        if (DATE_PATTERN != null) {
            startDate = LocalDateTime.parse(startValue, DATE_PATTERN);
            endDate = LocalDateTime.parse(endValue, DATE_PATTERN);
        } else {
            startDate = fitParse(startValue);
            endDate = fitParse(endValue);
        }
        //判定先后顺序
        while (startDate.compareTo(endDate) <= 0) {
            list.add(startDate.format(FORMAT_PATTERN));
            //针对间隔的优化判定
            if (FORMAT_PATTERN_CODE.contains("dd")) {
                startDate = startDate.plusDays(1);
            } else if (FORMAT_PATTERN_CODE.contains("MM")) {
                startDate = startDate.plusMonths(1);
            } else if (FORMAT_PATTERN_CODE.contains("yy")) {
                startDate = startDate.plusYears(1);
            }
        }
        String endText = endDate.format(FORMAT_PATTERN);
        if (!list.contains(endText)) {
            list.add(endText);
        }
        //循环赋值
        List<RouteInfo> newList = new ArrayList<>();
        for (RouteInfo routeInfo : routeInfos) {
            for (String text : list) {
                RouteInfo copy = routeInfo.copy();
                copy.setTable(new StringBuilder(routeInfo.getTable()).append("_").append(text).toString());
                newList.add(copy);
            }
        }
        return newList;
    }

    /**
     * 默认导向到最新的日期分片。
     *
     * @param tableConfig
     * @param routeInfo
     * @return
     */
    @Override
    public RouteInfo getDefaultRoute(MydbConfig.TableConfig tableConfig, RouteInfo routeInfo) throws RouteException {
        if (routeInfo.checkValid()) {
            //此处有性能问题，最好缓存当前时间
            String now = LocalDateTime.now().format(DATE_PATTERN_DEFAULT);
            return calculate(tableConfig, routeInfo, now);
        } else {
            return routeInfo;
        }
    }

    /**
     * 此方法用于返回创建表信息。
     * 对于日期类型，一般会向前进一天。
     *
     * @param tableConfig
     * @param routeInfos
     * @return
     */
    @Override
    public List<RouteInfo> getRouteListForCreate(MydbConfig.TableConfig tableConfig, List<RouteInfo> routeInfos) throws RouteException {
        List<String> list = new ArrayList<>();
        LocalDate today = LocalDate.now();
        list.add(today.format(FORMAT_PATTERN));
        for (int i = 0; i < prepareNum; i++) {
            //针对间隔的优化判定
            if (FORMAT_PATTERN_CODE.contains("dd")) {
                today = today.plusDays(1);
            } else if (FORMAT_PATTERN_CODE.contains("MM")) {
                today = today.plusMonths(1);
            } else if (FORMAT_PATTERN_CODE.contains("yy")) {
                today = today.plusYears(1);
            }
            list.add(today.format(FORMAT_PATTERN));
        }
        //循环赋值
        List<RouteInfo> newList = new ArrayList<>();
        for (RouteInfo routeInfo : routeInfos) {
            for (String text : list) {
                RouteInfo copy = routeInfo.copy();
                copy.setTable(new StringBuilder(routeInfo.getTable()).append("_").append(text).toString());
                newList.add(copy);
            }
        }
        return newList;
    }

    /**
     * 此方法用于返回创建表信息。
     *
     * @param tableConfig
     * @param routeInfos
     * @return
     */
    @Override
    public List<RouteInfo> getAllRouteList(MydbConfig.TableConfig tableConfig, List<RouteInfo> routeInfos) throws RouteException {
        //循环赋值
        List<RouteInfo> newList = new ArrayList<>();
        for (RouteInfo routeInfo : routeInfos) {
            List<String> list = SchemaCheckService.getTableList(routeInfo.getMysqlGroup(), routeInfo.getDatabase(), "^" + routeInfo.getTable() + "_[0-9]*$");
            if (list.size() == 0) {
                newList.add(routeInfo);
            } else {
                for (String tab : list) {
                    RouteInfo copy = routeInfo.copy();
                    copy.setTable(tab);
                    newList.add(copy);
                }
            }
        }
        return newList;
    }

    /**
     * 最大化适配解析。
     * @return
     */
    private final LocalDateTime fitParse(String dateValue){
        if (dateValue.length()>19){
            dateValue = dateValue.substring(0,19);
        }
        return LocalDateTime.parse(dateValue, DATE_PATTERN_DEFAULT);
    }

    /**
     * 日期类型快速格式化。
     * 大部分的日期，其实都是yyyyMMdd格式的，按照这个格式走就好。
     *
     * @return
     */
    private String quickFormat(String tableName, String value, String formatPattern) {
        StringBuilder sb = new StringBuilder(tableName).append("_");
        switch (formatPattern) {
            case "yyyyMMdd":
                if (value.length() >= 10) {
                    sb.append(value.substring(0, 4)).append(value.substring(5, 7)).append(value.substring(8, 10));
                }
                break;
            case "yyyyMM":
                if (value.length() >= 7) {
                    sb.append(value.substring(0, 4)).append(value.substring(5, 7));
                }
                break;
            case "yyyy":
                if (value.length() >= 4) {
                    sb.append(value.substring(0, 4));
                }
                break;
            case "yyMMdd":
                if (value.length() >= 10) {
                    sb.append(value.substring(2, 4)).append(value.substring(5, 7)).append(value.substring(8, 10));
                }
                break;
            case "yyMM":
                if (value.length() >= 7) {
                    sb.append(value.substring(2, 4)).append(value.substring(5, 7));
                }
                break;
            case "yy":
                if (value.length() >= 4) {
                    sb.append(value.substring(2, 4));
                }
                break;
            case "MMdd":
                if (value.length() >= 10) {
                    sb.append(value.substring(5, 7)).append(value.substring(8, 10));
                }
                break;
            case "MM":
                if (value.length() >= 7) {
                    sb.append(value.substring(5, 7));
                }
                break;
            case "dd":
                if (value.length() >= 10) {
                    sb.append(value.substring(8, 10));
                }
                break;
            default:
                break;
        }
        return sb.toString();
    }

}

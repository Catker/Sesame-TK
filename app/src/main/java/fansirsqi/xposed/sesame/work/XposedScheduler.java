package fansirsqi.xposed.sesame.work;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import fansirsqi.xposed.sesame.hook.ApplicationHook;
import fansirsqi.xposed.sesame.model.BaseModel;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.TimeUtil;

/**
 * Xposed 环境专用混合调度器
 * 🎯 设计目标：
 * 在Xposed环境中提供可靠的后台任务调度，结合Handler和JobService的优势
 * 📋 架构特点：
 * 1. 【双重调度】：Handler + JobService混合调度机制
 * 2. 【智能选择】：根据延迟时间和系统状态自动选择最佳调度方式
 * 3. 【无缝集成】：保持原有API接口不变，现有代码无需修改
 * 4. 【故障恢复】：JobService失败时自动回退到Handler
 * 🔧 调度策略：
 * - HANDLER_ONLY：仅使用Handler（快速响应，依赖进程存活）
 * - JOBSERVICE_ONLY：仅使用JobService（系统级调度，高可靠性）【默认】
 * - HYBRID：混合模式（短延迟用Handler，长延迟用JobService）
 * - AUTO：自动模式（根据系统状态智能选择）
 * 🚀 核心优势：
 * - 利用支付宝JobService基础设施，提升调度可靠性
 * - 保持Handler的快速响应能力
 * - 智能降级机制，确保任务不丢失
 * - 详细的状态监控和日志记录
 * - 完全向后兼容，无需修改现有调用代码
 * 💡 使用场景：
 * - 定时任务调度（支付宝签到、收能量等）
 * - 延迟执行任务（错误重试、网络恢复等）
 * - 精确时间执行（特定时间点触发）
 * - 唤醒任务（设备休眠后的定时唤醒）
 */
public class XposedScheduler {
    private static final String TAG = "XposedScheduler";
    
    /**
     * 任务类型常量定义
     * 📋 任务类型说明：
     * 🔄 PERIODIC - 周期性任务
     *    - 用途：定期重复执行的任务
     *    - 特点：执行完成后会自动调度下次执行
     *    - 场景：定时收蚂蚁森林能量、日常签到等
     *    - 调度：支持下次执行调度
     * ⏰ DELAYED - 延迟执行任务  
     *    - 用途：延迟一段时间后执行的任务
     *    - 特点：执行完成后会调度下次执行
     *    - 场景：错误重试、网络恢复后执行、定时任务等
     *    - 调度：支持下次执行调度
     * 🌅 WAKEUP - 定时唤醒任务
     *    - 用途：在特定时间点唤醒并执行任务
     *    - 特点：通常用于长时间延迟的定时任务
     *    - 场景：每日早晨定时启动、特定时间点执行等
     *    - 调度：不自动调度下次执行（单次执行）
     * 👆 MANUAL - 手动执行任务
     *    - 用途：用户主动触发或即时执行的任务
     *    - 特点：立即执行，不调度下次执行
     *    - 场景：用户点击按钮、测试执行、紧急任务等
     *    - 调度：不支持下次执行调度（一次性执行）
     */
    public static class TaskType {
        public static final String PERIODIC = "periodic";   // 周期性任务
        public static final String DELAYED = "delayed";     // 延迟执行任务
        public static final String WAKEUP = "wakeup";       // 定时唤醒任务  
        public static final String MANUAL = "manual";       // 手动执行任务
    }
    
    // 调度策略
    public static class ScheduleStrategy {
        public static final String AUTO = "auto";        // 自动选择
        public static final String HANDLER_ONLY = "handler";  // 仅使用Handler
        public static final String JOBSERVER_ONLY = "jobservice"; // 仅使用JobService
        public static final String HYBRID = "hybrid";    // 混合模式
    }
    
    private static final AtomicInteger taskIdCounter = new AtomicInteger(1000);
    private static final ConcurrentHashMap<Integer, Runnable> scheduledTasks = new ConcurrentHashMap<>();
    private static Handler mainHandler;
    private static boolean initialized = false;
    private static String currentStrategy = ScheduleStrategy.HYBRID;
    
    /**
     * 初始化调度器
     */
    public static void initialize(Context context) {
        try {
            if (!initialized) {
                if (mainHandler == null) {
                    mainHandler = new Handler(Looper.getMainLooper());
                }
    
                // 安装JobService Hook
                try {
                    JobServiceHook.installHook(context.getClassLoader());
                } catch (Exception e) {
                    Log.error(TAG, "JobService Hook安装失败: " + e.getMessage());
                }
                
                initialized = true;
                Log.record(TAG, "Xposed混合调度器初始化成功 - 当前策略: HYBRID混合模式（24小时抗假死优化）");
                logStatus();
            }
        } catch (Exception e) {
            Log.error(TAG, "Xposed调度器初始化失败: " + e.getMessage());
        }
    }
    
    /**
     * 调度延迟执行任务
     */
    @SuppressLint("DefaultLocale")
    public static void scheduleDelayedExecution(Context context, long delayMillis) {
        try {
            ensureInitialized(context);
            Log.record(TAG, String.format("调度延迟执行任务，延迟=%d秒，策略=%s", 
                delayMillis / 1000, currentStrategy));
            // 选择最佳调度策略
            boolean useJobService = shouldUseJobService(delayMillis);
            if (useJobService && JobServiceHook.scheduleJobServiceTask(context, delayMillis)) {
                Log.record(TAG, String.format("✓ 使用JobService调度成功，延迟=%d秒", delayMillis / 1000));
                return;
            }
            
            // 回退到Handler方式
            Log.record(TAG, useJobService ? "JobService调度失败，回退到Handler" : "选择Handler调度");
            int taskId = taskIdCounter.incrementAndGet();
            Runnable task = () -> {
                scheduledTasks.remove(taskId);
                executeTask(TaskType.DELAYED, taskId);
            };
            
            scheduledTasks.put(taskId, task);
            mainHandler.postDelayed(task, delayMillis);
            
            Log.record(TAG, String.format("使用Handler调度成功，ID=%d，延迟=%d秒", taskId, delayMillis / 1000));
        } catch (Exception e) {
            Log.error(TAG, "调度延迟执行任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 调度精确时间执行任务
     */
    @SuppressLint("DefaultLocale")
    public static void scheduleExactExecution(Context context, long delayMillis, long exactTimeMillis) {
        try {
            ensureInitialized(context);
            
            Log.record(TAG, String.format("调度精确执行任务，目标时间=%s", 
                TimeUtil.getCommonDate(exactTimeMillis)));
            
            // 优先使用JobService进行精确调度
            if (JobServiceHook.scheduleExactJobServiceTask(context, exactTimeMillis)) {
                Log.record(TAG, String.format("使用JobService精确调度成功，目标时间=%s", 
                    TimeUtil.getCommonDate(exactTimeMillis)));
                return;
            }
            
            // 回退到Handler方式
            int taskId = taskIdCounter.incrementAndGet();
            Runnable task = () -> {
                scheduledTasks.remove(taskId);
                executeTask(TaskType.PERIODIC, taskId);
            };
            
            scheduledTasks.put(taskId, task);
            mainHandler.postDelayed(task, delayMillis);
            
            Log.record(TAG, String.format("使用Handler精确调度，ID=%d，目标时间=%s", 
                taskId, TimeUtil.getCommonDate(exactTimeMillis)));
        } catch (Exception e) {
            Log.error(TAG, "调度精确执行任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 调度定时唤醒任务
     */@SuppressLint("DefaultLocale")
    public static void scheduleWakeupTask(Context context, long triggerAtMillis, String wakenTime) {
        try {
            ensureInitialized(context);
            
            long currentTime = System.currentTimeMillis();
            long delayMillis = triggerAtMillis - currentTime;
            
            if (delayMillis <= 0) {
                Log.record(TAG, "唤醒时间已过，跳过调度: " + wakenTime);
                return;
            }
            
            Log.record(TAG, String.format("调度定时唤醒任务，唤醒时间=%s，延迟=%d小时，策略=%s", 
                wakenTime, delayMillis / (1000 * 60 * 60), currentStrategy));
            
            // 优先使用JobService进行唤醒任务调度
            boolean useJobService = shouldUseJobService(delayMillis);
            
            if (useJobService && JobServiceHook.scheduleJobServiceTask(context, delayMillis)) {
                Log.record(TAG, String.format("✓ 使用JobService调度唤醒任务成功，时间=%s", wakenTime));
                return;
            }
            
            // 回退到Handler方式
            Log.record(TAG, useJobService ? "JobService唤醒调度失败，回退到Handler" : "选择Handler唤醒调度");
            int taskId = taskIdCounter.incrementAndGet();
            Runnable task = () -> {
                scheduledTasks.remove(taskId);
                executeTask(TaskType.WAKEUP, taskId);
            };
            
            scheduledTasks.put(taskId, task);
            mainHandler.postDelayed(task, delayMillis);
            
            Log.record(TAG, String.format("定时唤醒任务调度成功，ID=%d，时间=%s", taskId, wakenTime));
        } catch (Exception e) {
            Log.error(TAG, "调度定时唤醒任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 立即执行手动任务
     */
    public static void executeManualTask(Context context) {
        try {
            ensureInitialized(context);
            
            Log.record(TAG, "立即执行手动任务");
            
            // 在新线程中执行，避免阻塞主线程
            new Thread(() -> executeTask(TaskType.MANUAL, 0)).start();
        } catch (Exception e) {
            Log.error(TAG, "执行手动任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 立即执行延迟任务（会触发下次调度）
     * 用于处理时间已过的精确执行任务
     */
    public static void executeDelayedTaskImmediately(Context context) {
        try {
            ensureInitialized(context);
            
            Log.record(TAG, "立即执行延迟任务（时间已过的精确执行）");
            
            // 在新线程中执行，使用DELAYED类型确保会调度下次执行
            new Thread(() -> executeTask(TaskType.DELAYED, 0)).start();
        } catch (Exception e) {
            Log.error(TAG, "立即执行延迟任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 取消所有任务
     */
    public static void cancelAllTasks(Context context) {
        try {
            // 取消Handler任务
            if (mainHandler != null) {
                for (Runnable task : scheduledTasks.values()) {
                    mainHandler.removeCallbacks(task);
                }
                scheduledTasks.clear();
            }
            
            // 取消JobService任务
            JobServiceHook.cancelAllSesameJobs(context);
            
            Log.record(TAG, "已取消所有调度任务（Handler + JobService）");
        } catch (Exception e) {
            Log.error(TAG, "取消任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 执行任务的核心逻辑
     */
    @SuppressLint("DefaultLocale")
    private static void executeTask(String taskType, int taskId) {
        PowerManager.WakeLock wakeLock = null;
        long startTime = System.currentTimeMillis();
        
        try {
            // 根据任务类型显示详细信息
            String taskDescription = getTaskTypeDescription(taskType);
            Log.record(TAG, String.format("开始执行任务，类型=%s（%s），ID=%d", taskType, taskDescription, taskId));
            
            // 获取唤醒锁
            Context context = ApplicationHook.getAppContext();
            if (context != null) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sesame:XposedSchedulerWakeLock");
                    wakeLock.acquire(10 * 60 * 1000L); // 10分钟超时
                    Log.record(TAG, "已获取唤醒锁");
                }
            }
            
            // 设置线程名称
            Thread.currentThread().setName("XposedScheduler_" + taskType + "_" + taskId);
            
            // 执行核心任务
            ApplicationHook.getMainTask().startTask(false);
            
            // 调度下一次执行（仅对周期性任务）
            if (TaskType.PERIODIC.equals(taskType) || TaskType.DELAYED.equals(taskType)) {
                scheduleNextExecution();
                
                // 抗假死机制：额外调度一个备用任务（时间间隔x2）
                if (ScheduleStrategy.HYBRID.equals(currentStrategy)) {
                    scheduleBackupExecution();
                }
            }
            
            long executionTime = System.currentTimeMillis() - startTime;
            Log.record(TAG, String.format("任务执行完成，类型=%s（%s），ID=%d，耗时=%dms", 
                taskType, getTaskTypeDescription(taskType), taskId, executionTime));
                
            // 记录调度下次执行的情况
            if (TaskType.PERIODIC.equals(taskType) || TaskType.DELAYED.equals(taskType)) {
                Log.record(TAG, "🔄 准备调度下次执行...");
            } else {
                Log.record(TAG, String.format("⏹️ 任务类型 %s（%s）不需要调度下次执行", taskType, getTaskTypeDescription(taskType)));
            }
                
        } catch (Exception e) {
            Log.error(TAG, String.format("任务执行失败，类型=%s，ID=%d: %s", taskType, taskId, e.getMessage()));
            Log.printStackTrace(TAG, e);
        } finally {
            // 释放唤醒锁
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                    Log.record(TAG, "已释放唤醒锁");
                } catch (Exception e) {
                    Log.error(TAG, "释放唤醒锁失败: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 调度下一次执行
     */
    @SuppressLint("DefaultLocale")
    private static void scheduleNextExecution() {
        try {
            Context context = ApplicationHook.getAppContext();
            if (context == null) {
                Log.error(TAG, "❌ 无法获取应用上下文，跳过下次执行调度");
                return;
            }
            
            int checkInterval = BaseModel.getCheckInterval().getValue();
            Log.record(TAG, String.format("🔄 开始调度下次执行，间隔=%d秒", checkInterval / 1000));
            
            scheduleDelayedExecution(context, checkInterval);
            
            Log.record(TAG, String.format("✅ 已成功调度下次执行，延迟=%d秒", checkInterval / 1000));
        } catch (Exception e) {
            Log.error(TAG, "❌ 调度下次执行失败: " + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }
    
    /**
     * 调度备用执行（抗假死机制）
     * 在混合模式下，额外调度一个备用任务，防止主任务因为app假死而停止
     */
    @SuppressLint("DefaultLocale")
    private static void scheduleBackupExecution() {
        try {
            Context context = ApplicationHook.getAppContext();
            if (context == null) {
                Log.error(TAG, "❌ 无法获取应用上下文，跳过备用任务调度");
                return;
            }
            
            int checkInterval = BaseModel.getCheckInterval().getValue();
            long backupDelay = checkInterval * 2L; // 备用任务延迟是主任务的2倍
            
            Log.record(TAG, String.format("🛡️ 抗假死机制 - 调度备用任务，延迟=%d秒", backupDelay / 1000));
            
            // 备用任务强制使用JobService，提高可靠性
            if (JobServiceHook.scheduleJobServiceTask(context, backupDelay)) {
                Log.record(TAG, String.format("✅ 备用任务调度成功（JobService），延迟=%d秒", backupDelay / 1000));
            } else {
                // JobService失败时使用Handler作为最后保障
                int taskId = taskIdCounter.incrementAndGet();
                Runnable backupTask = () -> {
                    scheduledTasks.remove(taskId);
                    Log.record(TAG, "🔄 备用任务触发 - 检测到可能的假死，尝试恢复");
                    executeTask(TaskType.DELAYED, taskId);
                };
                
                scheduledTasks.put(taskId, backupTask);
                mainHandler.postDelayed(backupTask, backupDelay);
                
                Log.record(TAG, String.format("✅ 备用任务调度成功（Handler），ID=%d，延迟=%d秒", 
                    taskId, backupDelay / 1000));
            }
        } catch (Exception e) {
            Log.error(TAG, "❌ 调度备用任务失败: " + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }
    
    /**
     * 确保调度器已初始化
     */
    private static void ensureInitialized(Context context) {
        if (!initialized) {
            initialize(context);
        }
    }
    
    /**
     * 获取当前活跃的任务数量
     */
    public static int getActiveTaskCount() {
        return scheduledTasks.size();
    }
    
    /**
     * 获取任务类型的详细描述
     * 
     * @param taskType 任务类型
     * @return 任务类型的中文描述
     */
    private static String getTaskTypeDescription(String taskType) {
        switch (taskType) {
            case TaskType.PERIODIC:
                return "周期性任务，执行完会调度下次";
            case TaskType.DELAYED:
                return "延迟执行任务，执行完会调度下次";
            case TaskType.WAKEUP:
                return "定时唤醒任务，单次执行";
            case TaskType.MANUAL:
                return "手动执行任务，一次性执行";
            default:
                return "未知任务类型";
        }
    }
    
    /**
     * 获取调度策略的详细描述
     * 
     * @param strategy 调度策略
     * @return 策略的中文描述
     */
    private static String getStrategyDescription(String strategy) {
        switch (strategy) {
            case ScheduleStrategy.HANDLER_ONLY:
                return "仅使用Handler，快速响应";
            case ScheduleStrategy.JOBSERVER_ONLY:
                return "仅使用JobService，高可靠性";
            case ScheduleStrategy.HYBRID:
                return "混合模式，24小时抗假死";
            case ScheduleStrategy.AUTO:
                return "自动模式，智能选择";
            default:
                return "未知策略";
        }
    }
    
    /**
     * 设置调度策略
     */
    public static void setScheduleStrategy(String strategy) {
        currentStrategy = strategy;
        Log.record(TAG, "调度策略已设置为: " + strategy);
    }
    
    /**
     * 获取当前调度策略
     */
    public static String getScheduleStrategy() {
        return currentStrategy;
    }
    
    /**
     * 判断是否应该使用JobService
     * <p>
     * 调度策略详解：
     * <p>
     * 1. HANDLER_ONLY：仅使用Handler
     *    - 适用于对时机要求严格的场景
     *    - 应用进程存活时调度准确
     * <p>
     * 2. JOBSERVICE_ONLY：仅使用JobService（当前默认）
     *    - 利用系统级调度，更可靠
     *    - 即使应用被杀死也能执行
     *    - 适合后台保活场景
     * <p>
     * 3. HYBRID：混合模式
     *    - 短延迟(<1分钟)用Handler（快速响应）
     *    - 长延迟(>1分钟)用JobService（可靠性）
     * <p>
     * 4. AUTO：自动模式
     *    - 根据延迟时间和系统负载智能选择
     *    - 超长延迟(>2分钟)优先JobService
     *    - Handler任务过多时切换JobService
     * 
     * @param delayMillis 延迟时间（毫秒）
     * @return 是否使用JobService
     */
    @SuppressLint("DefaultLocale")
    private static boolean shouldUseJobService(long delayMillis) {
        switch (currentStrategy) {
            case ScheduleStrategy.HANDLER_ONLY:
                // 强制使用Handler模式
                return false;
            case ScheduleStrategy.JOBSERVER_ONLY:
                // JobService优先模式：只要JobService可用就使用
                boolean available = JobServiceHook.isJobServiceAvailable();
                Log.record(TAG, String.format("JobService优先模式 - JobService可用性: %s, 延迟: %d秒", 
                    available, delayMillis / 1000));
                return available;
                
            case ScheduleStrategy.HYBRID:
                // 混合模式：抗假死优化调度策略
                // 策略1：短延迟(<30秒)用Handler（快速响应）
                // 策略2：中延迟(30秒-5分钟)根据系统状态选择
                // 策略3：长延迟(>5分钟)优先JobService（抗假死）
                if (!JobServiceHook.isJobServiceAvailable()) {
                    return false;
                }
                
                // 短延迟：快速响应，使用Handler
                if (delayMillis < 30000) {
                    Log.record(TAG, "混合模式 - 短延迟使用Handler，快速响应");
                    return false;
                }
                
                // 长延迟：抗假死，强制使用JobService
                if (delayMillis > 300000) { // 5分钟
                    Log.record(TAG, "混合模式 - 长延迟使用JobService，抗假死保障");
                    return true;
                }
                // 中延迟：根据Handler负载决策（防止Handler阻塞）
                boolean useJobService = scheduledTasks.size() > 2;
                Log.record(TAG, String.format("混合模式 - 中延迟智能选择: %s (Handler任务数=%d)", 
                    useJobService ? "JobService" : "Handler", scheduledTasks.size()));
                return useJobService;
            case ScheduleStrategy.AUTO:
            default:
                // 自动模式：智能选择最佳策略
                if (!JobServiceHook.isJobServiceAvailable()) {
                    return false;
                }
                // 条件1：超长延迟（>2分钟）优先JobService
                // 条件2：中等延迟（>30秒）且Handler负载较高时使用JobService
                return delayMillis > 120000 || (delayMillis > 30000 && scheduledTasks.size() > 3);
        }
    }
    
    /**
     * 记录调度器状态
     */
    @SuppressLint("DefaultLocale")
    public static void logStatus() {
        try {
            String strategyDescription = getStrategyDescription(currentStrategy);
            String status = String.format("【Xposed混合调度器状态】\n" +
                "├─ 初始化状态: %s\n" +
                "├─ 当前策略: %s (%s)\n" +
                "├─ Handler任务数: %d\n" +
                "├─ JobService可用性: %s\n" +
                "└─ 24小时运行: %s", 
                initialized ? "✓已初始化" : "✗未初始化", 
                currentStrategy,
                strategyDescription,
                scheduledTasks.size(),
                JobServiceHook.isJobServiceAvailable() ? "✓可用" : "✗不可用",
                ScheduleStrategy.HYBRID.equals(currentStrategy) ? "✓抗假死优化" : "基础模式");
            
            Log.record(TAG, status);
            JobServiceHook.logStatus();
        } catch (Exception e) {
            Log.error(TAG, "获取调度器状态失败: " + e.getMessage());
        }
    }
}

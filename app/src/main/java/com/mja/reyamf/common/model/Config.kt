package com.mja.reyamf.common.model

/**
 * reYAMF全局配置数据类
 *
 * 包含所有用户可配置的选项和系统行为设置。
 * 配置会被序列化为JSON格式存储在系统数据目录中。
 */
data class Config(
    // ========== 显示和渲染设置 ==========

    /**
     * DPI减少值（默认50）
     * 用于优化应用在小窗口中的显示效果，值越大应用界面越小
     * 建议范围：50-100，过大可能导致应用崩溃
     */
    var reduceDPI: Int = 50,

    /**
     * 虚拟显示器标志位组合（默认1668）
     *
     * 标志位说明：
     * - VIRTUAL_DISPLAY_FLAG_SECURE (4): 支持FLAG_SECURE应用
     * - VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT (128): 跟随内容旋转
     * - VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS (512): 显示系统装饰
     * - VIRTUAL_DISPLAY_FLAG_TRUSTED (1024): 受信任的显示器
     *
     * 1668 = 4 + 128 + 512 + 1024
     */
    var flags: Int = 1668,

    /** 是否启用彩色控制器（默认false） */
    var coloredController: Boolean = false,

    /**
     * 窗口化策略（默认0）
     *
     * 选项说明：
     * - 0: 仅移动任务（move task only）
     * - 1: 仅启动活动（start activity only）
     * - 2: 移动任务，失败时回退到启动活动（move task, failback to start activity）
     */
    var windowfy: Int = 0,

    /**
     * Surface视图类型（默认1）
     *
     * 选项说明：
     * - 0: TextureView（纹理视图，更灵活但性能稍低）
     * - 1: SurfaceView（表面视图，性能更好但限制较多）
     */
    var surfaceView: Int = 1,

    // ========== 行为设置 ==========

    /** 从最近任务启动后是否返回桌面（默认false） */
    var recentBackHome: Boolean = false,

    /** 是否在窗口中显示输入法（默认false） */
    var showImeInWindow: Boolean = false,

    /** 是否显示强制显示输入法选项（默认false） */
    var showForceShowIME: Boolean = false,

    // ========== 窗口尺寸和位置 ==========

    /** 默认窗口宽度（dp，默认280） */
    var defaultWindowWidth: Int = 280,

    /** 默认窗口高度（dp，默认380） */
    var defaultWindowHeight: Int = 380,

    /** 竖屏模式下窗口Y轴偏移（默认0） */
    var portraitY: Int = 0,

    /** 横屏模式下窗口Y轴偏移（默认0） */
    var landscapeY: Int = 0,

    /** 窗口圆角大小（默认20） */
    var windowRoundedCorner: Int = 20,

    // ========== 侧边栏设置 ==========

    /** 收藏应用列表 */
    var favApps: MutableList<FavApps> = mutableListOf(),

    /** 是否在开机时启动侧边栏（默认false） */
    var launchSideBarAtBoot: Boolean = false,

    /** 是否启用侧边栏功能（默认true） */
    var enableSidebar: Boolean = true,

    /** 侧边栏透明度（0-100，默认80） */
    var sidebarTransparency: Int = 80,

    /**
     * 侧边栏位置（默认false）
     * - false: 左侧
     * - true: 右侧
     */
    var sidebarPosition: Boolean = false,

    // ========== Hook配置 ==========

    /** 启动器Hook配置 */
    var hookLauncher: HookLauncher = HookLauncher(),
) {
    /**
     * 启动器Hook配置子类
     *
     * 控制在启动器中启用哪些Hook功能
     */
    data class HookLauncher(
        /** 是否Hook最近任务界面（默认true） */
        var hookRecents: Boolean = true,

        /** 是否Hook任务栏（默认true） */
        var hookTaskbar: Boolean = true,

        /** 是否Hook应用长按弹出菜单（默认true） */
        var hookPopup: Boolean = true,

        /** 是否Hook透明任务栏（默认false，实验性功能） */
        var hookTransientTaskbar: Boolean = false,
    )
}
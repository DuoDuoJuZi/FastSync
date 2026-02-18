/*
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-19
 */
use tray_icon::{
    menu::{Menu, MenuEvent, MenuItem},
    MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent,
};
use tao::{
    event::Event,
    event_loop::{ControlFlow, EventLoopBuilder},
};
use local_ip_address::list_afinet_netifas;

#[derive(Debug)]
enum UserEvent {
    TrayIconEvent(tray_icon::TrayIconEvent),
    MenuEvent(tray_icon::menu::MenuEvent),
}

/// 运行系统托盘事件循环。
/// 该函数会阻塞当前线程，直到应用程序退出。
pub fn run_event_loop() {
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();

    let proxy_clone = proxy.clone();
    TrayIconEvent::set_event_handler(Some(move |event| {
        let _ = proxy_clone.send_event(UserEvent::TrayIconEvent(event));
    }));

    let proxy_clone = proxy.clone();
    MenuEvent::set_event_handler(Some(move |event| {
        let _ = proxy_clone.send_event(UserEvent::MenuEvent(event));
    }));

    let tray_menu = Menu::new();
    let quit_i = MenuItem::new("退出", true, None);
    tray_menu.append(&quit_i).unwrap();

    let icon_path = std::path::Path::new("icon.ico");
    let icon = load_icon(icon_path).expect("Failed to load icon.ico");

    let mut tray_icon = Some(
        TrayIconBuilder::new()
            .with_menu(Box::new(tray_menu))
            .with_tooltip("FastSync Server")
            .with_icon(icon)
            .build()
            .unwrap(),
    );

    let current_ip = get_best_local_ip().unwrap_or_else(|| "Unknown".into());

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;

        match event {
            Event::UserEvent(UserEvent::MenuEvent(event)) => {
                if event.id == quit_i.id() {
                    tray_icon.take(); 
                    *control_flow = ControlFlow::Exit;
                }
            }
            Event::UserEvent(UserEvent::TrayIconEvent(event)) => {
                match event {
                    TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } => {
                        let msg = format!("FastSync 运行中 - IP: {}", current_ip);

                        std::thread::spawn(move || {
                            rfd::MessageDialog::new()
                                .set_title("FastSync Info")
                                .set_description(&msg)
                                .show();
                        });
                    }
                    _ => {}
                }
            }
            _ => {}
        }
    });
}

/// 加载本地 icon.ico 文件。
///
/// # Arguments
/// * `path` - 图标文件路径
fn load_icon(path: &std::path::Path) -> Option<tray_icon::Icon> {
    let (icon_rgba, icon_width, icon_height) = {
        let image = image::open(path).ok()?.into_rgba8();
        let (width, height) = image.dimensions();
        let rgba = image.into_raw();
        (rgba, width, height)
    };
    tray_icon::Icon::from_rgba(icon_rgba, icon_width, icon_height).ok()
}

/// 获取局域网 IP 地址。
/// 优先 192.168.x.x，其次 10.x.x.x 或 172.x.x.x，
/// 并排除常见的虚拟网卡名称。
fn get_best_local_ip() -> Option<String> {
    let interfaces = list_afinet_netifas().ok()?;
    
    let mut candidates = Vec::new();
    
    for (name, ip) in interfaces {
        let ip_str = ip.to_string();
        if ip_str.starts_with("127.") || ip_str.starts_with("169.254.") {
            continue;
        }
        
        let name_lower = name.to_lowercase();
        let is_virtual = name_lower.contains("wsl") 
            || name_lower.contains("docker") 
            || name_lower.contains("vethernet") 
            || name_lower.contains("tailscale")
            || name_lower.contains("meta")
            || name_lower.contains("loopback");
            
        let priority = if ip_str.starts_with("192.168.") {
            3
        } else if ip_str.starts_with("10.") || ip_str.starts_with("172.") {
            2
        } else {
            1
        };
        
        candidates.push((priority, !is_virtual, ip_str));
    }
    
    candidates.sort_by(|a, b| {
        b.0.cmp(&a.0) 
            .then(b.1.cmp(&a.1)) 
    });
    
    candidates.first().map(|c| c.2.clone())
}

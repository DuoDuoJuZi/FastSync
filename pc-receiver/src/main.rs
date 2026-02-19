/*
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-18
 */
#![windows_subsystem = "windows"]
use axum::{
    extract::DefaultBodyLimit,
    routing::post,
    Router,
};
use std::net::SocketAddr;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use local_ip_address::local_ip;
use winreg::enums::*;
use winreg::RegKey;

mod tray;
mod handlers;

pub const APP_ID: &str = "com.duoduojuzi.fastsync";

/// 应用程序入口点。
fn main() {
    tracing_subscriber::fmt::init();

    register_app_id();

    std::panic::set_hook(Box::new(|info| {
        let msg = format!("程序发生致命错误:\n{}", info);
        std::thread::spawn(move || {
             rfd::MessageDialog::new()
                .set_title("FastSync Error")
                .set_description(&msg)
                .set_level(rfd::MessageLevel::Error)
                .show();
        }).join().unwrap();
    }));

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();

    rt.spawn(async {
        start_mdns_broadcast();

        let app = Router::new()
            .route("/upload", post(handlers::photo::upload))
            .route("/sms", post(handlers::sms::receive_sms))
            .layer(DefaultBodyLimit::max(50 * 1024 * 1024));

        let addr = SocketAddr::from(([0, 0, 0, 0], 3000));
        tracing::info!("Server listening on {}", addr);

        let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
        axum::serve(listener, app).await.unwrap();
    });

    tray::run_event_loop();
}

/// 注册 `_photosync._tcp.local.` mDNS 服务，广播主机名与 IP 地址。
fn start_mdns_broadcast() {
    let mdns = ServiceDaemon::new().expect("Failed to create mDNS daemon");
    
    let hostname = hostname::get()
        .unwrap_or_else(|_| "fast-sync-pc".into())
        .to_string_lossy()
        .to_string();
        
    let service_type = "_photosync._tcp.local.";
    let instance_name = format!("{}_fastsync", hostname);
    
    let my_ip = match local_ip() {
        Ok(ip) => ip,
        Err(e) => {
            tracing::error!("Failed to get local IP address: {:?}", e);
            return;
        }
    };
    
    let ip_str = my_ip.to_string();
    let port = 3000;
    
    tracing::info!("Starting mDNS broadcast on IP: {}", ip_str);

    let properties: HashMap<String, String> = HashMap::new();

    let my_service = ServiceInfo::new(
        service_type,
        &instance_name,
        &format!("{}.local.", instance_name),
        &ip_str,
        port,
        Some(properties),
    ).expect("valid service info");

    mdns.register(my_service).expect("Failed to register mDNS service");
    
    Box::leak(Box::new(mdns));
    
    tracing::info!("mDNS service registered: {} ({}) @ {}:{}", instance_name, service_type, ip_str, port);
}

/// 注册应用程序 ID 并创建快捷方式，确保通知正常工作。
fn register_app_id() {
    let exe_path = std::env::current_exe().unwrap_or_default();
    
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let path = format!("Software\\Classes\\AppUserModelId\\{}", APP_ID);
    if let Ok((key, _)) = hkcu.create_subkey(&path) {
        let _ = key.set_value("DisplayName", &"FastSync Receiver");
    } else {
        tracing::error!("Failed to register AppUserModelId in registry");
    }

    if let Some(mut start_menu) = dirs::data_local_dir() {
        start_menu.push("Microsoft\\Windows\\Start Menu\\Programs");
        if !start_menu.exists() {
            let _ = std::fs::create_dir_all(&start_menu);
        }
        
        let shortcut_path = start_menu.join("FastSync.lnk");
        
        let script = format!(
            "$ws = New-Object -ComObject WScript.Shell; \
            $s = $ws.CreateShortcut('{}'); \
            $s.TargetPath = '{}'; \
            $s.WorkingDirectory = '{}'; \
            $s.AppUserModelID = '{}'; \
            $s.Save()", 
            shortcut_path.to_string_lossy(),
            exe_path.to_string_lossy(),
            exe_path.parent().unwrap_or(&exe_path).to_string_lossy(),
            APP_ID
        );
        
        let _ = std::process::Command::new("powershell")
            .args(["-NoProfile", "-Command", &script])
            .output();
            
        tracing::info!("Shortcut created/updated at: {:?}", shortcut_path);
    }
}

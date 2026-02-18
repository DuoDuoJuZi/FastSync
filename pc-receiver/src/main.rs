/*
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-18
 */
use axum::{
    extract::{DefaultBodyLimit, Multipart},
    http::StatusCode,
    routing::post,
    Router,
};
use std::net::SocketAddr;
use std::sync::Arc;
use windows::{
    core::*,
    Data::Xml::Dom::XmlDocument,
    UI::Notifications::ToastNotificationManager,
    Foundation::{DateTime, IReference, PropertyValue},
};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    start_mdns_broadcast();

    let app = Router::new()
        .route("/upload", post(upload))
        .layer(DefaultBodyLimit::max(50 * 1024 * 1024));

    let addr = SocketAddr::from(([0, 0, 0, 0], 3000));
    tracing::info!("Server listening on {}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

/// 启动 mDNS 服务广播，使局域网设备能发现此 PC。
fn start_mdns_broadcast() {
    let mdns = ServiceDaemon::new().expect("Failed to create mDNS daemon");
    
    let hostname = hostname::get()
        .unwrap_or_else(|_| "fast-sync-pc".into())
        .to_string_lossy()
        .to_string();
        
    let service_type = "_photosync._tcp.local.";
    let instance_name = format!("{}_fastsync", hostname);
    let ip = "0.0.0.0"; 
    let port = 3000;
    
    let properties: HashMap<String, String> = HashMap::new();

    let my_service = ServiceInfo::new(
        service_type,
        &instance_name,
        &format!("{}.local.", instance_name),
        ip,
        port,
        Some(properties),
    ).expect("valid service info");

    mdns.register(my_service).expect("Failed to register mDNS service");
    
    Box::leak(Box::new(mdns));
    
    tracing::info!("mDNS service registered: {} ({})", instance_name, service_type);
}

/// 处理图片上传请求。
///
/// # Arguments
/// * `multipart` - 包含图片数据的 Multipart 表单
///
/// # Returns
/// HTTP 状态码（200 OK 表示接收成功）
async fn upload(mut multipart: Multipart) -> StatusCode {
    let mut image_data = None;

    while let Some(field) = multipart.next_field().await.unwrap_or(None) {
        let name = field.name().unwrap_or("").to_string();
        let data = field.bytes().await.unwrap_or_default();

        if name == "data" {
            image_data = Some(data.to_vec());
        }
    }

    if let Some(data) = image_data {
        tracing::info!("Image received successfully, size: {} bytes", data.len());
        
        tokio::spawn(async move {
            if let Some(temp_file_path) = save_temp_image(&data) {
                if let Err(e) = show_notification_with_actions(data, temp_file_path) {
                    tracing::error!("Failed to show notification: {:?}", e);
                }
            }
        });

        return StatusCode::OK;
    } else {
        tracing::error!("Missing data");
    }

    StatusCode::BAD_REQUEST
}

/// 将图片数据保存到临时目录。
///
/// # Arguments
/// * `data` - 图片二进制数据
///
/// # Returns
/// 保存的文件绝对路径，若失败则返回 None
fn save_temp_image(data: &[u8]) -> Option<String> {
    use std::io::Write;
    let temp_dir = std::env::temp_dir();
    let file_name = format!("fastsync_{}.png", chrono::Utc::now().timestamp_millis());
    let file_path = temp_dir.join(file_name);
    
    if let Ok(mut file) = std::fs::File::create(&file_path) {
        if let Ok(_) = file.write_all(data) {
            return Some(file_path.to_string_lossy().to_string());
        }
    }
    None
}

/// 显示带有交互按钮的 Windows Toast 通知。
///
/// # Arguments
/// * `image_data` - 图片原始数据（用于后续操作）
/// * `image_path` - 本地预览图片路径
///
/// # Returns
/// 操作结果 Result
fn show_notification_with_actions(image_data: Vec<u8>, image_path: String) -> windows::core::Result<()> {
    let toast_xml = XmlDocument::new()?;
    
    let image_xml = format!(r#"<image placement='hero' src='file:///{}'/>"#, image_path.replace("\\", "/"));
    
    let xml_string = format!(r#"
        <toast duration="long" activationType='background'>
            <visual>
                <binding template='ToastGeneric'>
                    <text>收到手机图片</text>
                    <text>请选择操作（30秒后自动忽略）</text>
                    {}
                </binding>
            </visual>
            <actions>
                <action content='保存到本地' arguments='save' />
                <action content='复制到剪切板' arguments='copy' />
                <action content='忽略' arguments='ignore' />
            </actions>
        </toast>
    "#, image_xml);

    toast_xml.LoadXml(&HSTRING::from(xml_string))?;

    let notification = windows::UI::Notifications::ToastNotification::CreateToastNotification(&toast_xml)?;

    notification.SetTag(&HSTRING::from("CurrentPhoto"))?;
    notification.SetGroup(&HSTRING::from("FastSync"))?;

    let now_unix_millis = chrono::Utc::now().timestamp_millis();
    let expiration_millis = now_unix_millis + 30_000; 
    let expiration_ticks = (expiration_millis * 10_000) + 116444736000000000;
    
    let expiry_time = DateTime { UniversalTime: expiration_ticks };
    
    let expiry_inspectable = PropertyValue::CreateDateTime(expiry_time)?;
    let expiry_reference: IReference<DateTime> = expiry_inspectable.cast()?;
    notification.SetExpirationTime(&expiry_reference)?;

    let image_data = Arc::new(image_data);
    let image_data_clone = image_data.clone();
    
    notification.Activated(&windows::Foundation::TypedEventHandler::new(move |_sender, args: &Option<IInspectable>| {
        if let Some(args) = args {
            let args: windows::UI::Notifications::ToastActivatedEventArgs = args.cast()?;
            let arguments = args.Arguments()?.to_string();
            
            if arguments == "save" {
                tracing::info!("Save action clicked");
                save_file_dialog(&image_data_clone);
            } else if arguments == "copy" {
                tracing::info!("Copy action clicked");
                copy_to_clipboard(&image_data_clone);
            } else if arguments == "ignore" {
                tracing::info!("Ignore action clicked");
            }
        }
        Ok(())
    }))?;
    
    let notifier = ToastNotificationManager::CreateToastNotifierWithId(h!("FastSync.Receiver"))?;
    notifier.Show(&notification)?;
    
    Box::leak(Box::new(notification));
    
    Ok(())
}

/// 将图片数据写入系统剪贴板。
///
/// # Arguments
/// * `data` - 图片二进制数据
fn copy_to_clipboard(data: &[u8]) {
    match image::load_from_memory(data) {
        Ok(img) => {
            let rgba = img.to_rgba8();
            let width = rgba.width() as usize;
            let height = rgba.height() as usize;
            let bytes = rgba.into_raw();
            
            let image_data = arboard::ImageData {
                width,
                height,
                bytes: std::borrow::Cow::Owned(bytes),
            };
            
            match arboard::Clipboard::new() {
                Ok(mut clipboard) => {
                    if let Err(e) = clipboard.set_image(image_data) {
                        tracing::error!("Failed to set clipboard image: {:?}", e);
                    } else {
                        tracing::info!("Image copied to clipboard successfully");
                    }
                },
                Err(e) => tracing::error!("Failed to initialize clipboard: {:?}", e),
            }
        },
        Err(e) => tracing::error!("Failed to decode image: {:?}", e),
    }
}

/// 弹出文件保存对话框并保存图片。
///
/// # Arguments
/// * `data` - 图片二进制数据
fn save_file_dialog(data: &[u8]) {
    let task = rfd::FileDialog::new()
        .set_file_name("image.png")
        .add_filter("Image", &["png", "jpg", "jpeg"])
        .save_file();

    if let Some(path) = task {
        use std::io::Write;
        if let Ok(mut file) = std::fs::File::create(&path) {
            if let Err(e) = file.write_all(data) {
                tracing::error!("Failed to write file to {:?}: {:?}", path, e);
            } else {
                tracing::info!("File saved successfully to {:?}", path);
            }
        }
    }
}

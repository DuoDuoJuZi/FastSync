/*
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-20
 *
 * 剪贴板处理器模块。
 * 负责接收手机端推送的剪贴板内容，并显示交互式通知。
 */
use axum::{
    extract::Json,
    http::StatusCode,
};
use serde::Deserialize;
use windows::{
    core::*,
    Data::Xml::Dom::XmlDocument,
    UI::Notifications::{ToastNotification, ToastNotificationManager},
    Foundation::{DateTime, IReference, PropertyValue},
};
use crate::APP_ID;
use crate::handlers::store_notification;

/// 剪贴板数据载荷结构体。
/// 用于反序列化接收到的 JSON 数据。
#[derive(Debug, Deserialize)]
pub struct ClipboardPayload {
    pub text: String,
    pub timestamp: i64,
}

/// 处理剪贴板同步请求。
/// 
/// 接收手机端发送的剪贴板内容，并不直接写入系统剪贴板，
/// 
/// # 参数
/// * `payload` - 包含剪贴板文本和时间戳的 JSON 数据
pub async fn receive_clipboard(Json(payload): Json<ClipboardPayload>) -> StatusCode {
    tracing::info!("Received clipboard content, length: {}", payload.text.len());
    
    // 显示通知，由用户交互决定是否写入剪贴板
    if let Err(e) = show_clipboard_notification(&payload.text) {
        tracing::error!("Failed to show clipboard notification: {:?}", e);
    }
    
    StatusCode::OK
}

/// 显示剪贴板同步通知。
/// 
/// 创建一个带有交互按钮的 Windows Toast 通知。
fn show_clipboard_notification(text: &str) -> windows::core::Result<()> {
    let toast_xml = XmlDocument::new()?;
    
    let preview = if text.chars().count() > 100 {
        format!("{}...", text.chars().take(100).collect::<String>())
    } else {
        text.to_string()
    };
    
    let content_escaped = preview.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    
    let xml_string = format!(r#"
        <toast duration="short" activationType='background'>
        <visual>
            <binding template='ToastGeneric'>
                <text>收到手机剪贴板</text>
                <text>{}</text>
            </binding>
        </visual>
        <actions>
            <action content='复制' arguments='copy_clipboard' activationType="foreground"/>
            <action content='忽略' arguments='ignore' activationType="foreground"/>
        </actions>
        </toast>
    "#, content_escaped);

    toast_xml.LoadXml(&HSTRING::from(xml_string))?;

    let notification = ToastNotification::CreateToastNotification(&toast_xml)?;

    notification.SetTag(&HSTRING::from("clipboard_sync"))?;
    notification.SetGroup(&HSTRING::from("FastSync"))?;
    
    let now_unix_millis = chrono::Utc::now().timestamp_millis();
    let expiration_millis = now_unix_millis + 30_000; 
    let expiration_ticks = (expiration_millis * 10_000) + 116444736000000000;
    
    let expiry_time = DateTime { UniversalTime: expiration_ticks };
    let expiry_inspectable = PropertyValue::CreateDateTime(expiry_time)?;
    let expiry_reference: IReference<DateTime> = expiry_inspectable.cast()?;
    notification.SetExpirationTime(&expiry_reference)?;

    let text_content = text.to_string();
    notification.Activated(&windows::Foundation::TypedEventHandler::new(move |_sender, args: &Option<IInspectable>| {
        if let Some(args) = args {
            let args: windows::UI::Notifications::ToastActivatedEventArgs = args.cast()?;
            let arguments = args.Arguments()?.to_string();
            
            if arguments == "copy_clipboard" {
                tracing::info!("Copy clipboard action clicked");
                crate::handlers::photo::copy_text_to_clipboard(&text_content);
            } else if arguments == "ignore" {
                tracing::info!("Ignore clipboard action clicked");
            }
        }
        Ok(())
    }))?;

    let notifier = ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(APP_ID))?;
    notifier.Show(&notification)?;
    
    store_notification("clipboard", notification);
    
    Ok(())
}

/*
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-19
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

/// 短信数据载荷结构体。
#[derive(Debug, Deserialize)]
pub struct SmsPayload {
    pub sender: String,
    pub content: String,
    pub code: String,
}

/// 处理短信上传请求。
///
/// # Arguments
/// * `payload` - 包含短信信息的 JSON 数据
///
/// # Returns
/// HTTP 状态码
pub async fn receive_sms(Json(payload): Json<SmsPayload>) -> StatusCode {
    tracing::info!("Received SMS from {}: {}", payload.sender, payload.content);
    
    if let Err(e) = show_sms_notification(&payload) {
        tracing::error!("Failed to show SMS notification: {:?}", e);
    }
    
    StatusCode::OK
}

/// 显示带有交互按钮的 Windows Toast 通知 (短信)。
///
/// # Arguments
/// * `payload` - 短信数据载荷
///
/// # Returns
/// 操作结果 Result
fn show_sms_notification(payload: &SmsPayload) -> windows::core::Result<()> {
    let toast_xml = XmlDocument::new()?;
    
    let sender_escaped = payload.sender.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    let content_escaped = payload.content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    
    let mut actions_xml = String::from(r#"
            <action content='复制原文' arguments='copy_content'/>
    "#);

    if !payload.code.is_empty() {
         actions_xml.push_str(r#"
            <action content='复制验证码' arguments='copy_code'/>
         "#);
    }
    
    actions_xml.push_str(r#"
            <action content='忽略' arguments='ignore'/>
    "#);

    let xml_string = format!(r#"
        <toast duration="long" activationType='background'>
        <visual>
            <binding template='ToastGeneric'>
                <text>收到手机短信 - {}</text>
                <text>{}</text>
            </binding>
        </visual>
        <actions>
            {}
        </actions>
        </toast>
    "#, sender_escaped, content_escaped, actions_xml);

    toast_xml.LoadXml(&HSTRING::from(xml_string))?;

    let notification = ToastNotification::CreateToastNotification(&toast_xml)?;

    notification.SetTag(&HSTRING::from("sms_sync"))?;
    notification.SetGroup(&HSTRING::from("FastSync"))?;

    let now_unix_millis = chrono::Utc::now().timestamp_millis();
    let expiration_millis = now_unix_millis + 60_000; 
    let expiration_ticks = (expiration_millis * 10_000) + 116444736000000000;
    
    let expiry_time = DateTime { UniversalTime: expiration_ticks };
    let expiry_inspectable = PropertyValue::CreateDateTime(expiry_time)?;
    let expiry_reference: IReference<DateTime> = expiry_inspectable.cast()?;
    notification.SetExpirationTime(&expiry_reference)?;

    let content = payload.content.clone();
    let code = payload.code.clone();
    
    notification.Activated(&windows::Foundation::TypedEventHandler::new(move |_sender, args: &Option<IInspectable>| {
        if let Some(args) = args {
            let args: windows::UI::Notifications::ToastActivatedEventArgs = args.cast()?;
            let arguments = args.Arguments()?.to_string();
            
            if arguments == "copy_content" {
                tracing::info!("Copy SMS content clicked");
                crate::handlers::photo::copy_text_to_clipboard(&content);
            } else if arguments == "copy_code" {
                tracing::info!("Copy verification code clicked");
                crate::handlers::photo::copy_text_to_clipboard(&code);
            } else if arguments == "ignore" {
                tracing::info!("Ignore SMS action clicked");
            }
        }
        Ok(())
    }))?;
    
    let notifier = ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(APP_ID))?;
    notifier.Show(&notification)?;
    
    // 使用全局存储管理生命周期
    store_notification("sms", notification);
    
    Ok(())
}

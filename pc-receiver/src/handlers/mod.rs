/*
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-19
 */
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use windows::UI::Notifications::ToastNotification;

pub mod photo;
pub mod sms;
pub mod clipboard;

pub static NOTIFICATION_STORAGE: OnceLock<Mutex<HashMap<String, ToastNotification>>> = OnceLock::new();

/// 存储通知对象，用于后续操作。
///
/// # Arguments
/// * `tag` - 通知的唯一标识符
/// * `notification` - 要存储的 ToastNotification 对象
pub fn store_notification(tag: &str, notification: ToastNotification) {
    let storage = NOTIFICATION_STORAGE.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(mut map) = storage.lock() {
        map.insert(tag.to_string(), notification);
    }
}

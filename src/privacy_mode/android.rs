// Android Privacy Mode Implementation
// Uses Flutter platform channel to control black screen overlay

use hbb_common::{bail, ResultType};
use std::sync::atomic::{AtomicI32, Ordering};

pub const PRIVACY_MODE_IMPL: &str = "privacy_mode_impl_android";

static CONN_ID: AtomicI32 = AtomicI32::new(super::INVALID_PRIVACY_MODE_CONN_ID);

pub struct PrivacyModeImpl {
    impl_key: String,
}

impl PrivacyModeImpl {
    pub fn new(impl_key: &str) -> Self {
        Self {
            impl_key: impl_key.to_string(),
        }
    }
}

impl super::PrivacyMode for PrivacyModeImpl {
    fn is_async_privacy_mode(&self) -> bool {
        false
    }

    fn init(&self) -> ResultType<()> {
        Ok(())
    }

    fn clear(&mut self) {
        let _ = self.turn_off_privacy(super::INVALID_PRIVACY_MODE_CONN_ID, None);
    }

    fn turn_on_privacy(&mut self, conn_id: i32) -> ResultType<bool> {
        // Check if already occupied
        if self.check_on_conn_id(conn_id)? {
            return Ok(true);
        }

        hbb_common::log::info!("Turning on Android privacy mode, conn_id: {}", conn_id);

        // Request Flutter to show black screen overlay
        match scrap::android::call_main_service_set_by_name(
            "toggle_privacy_mode",
            Some("true"),
            None,
        ) {
            Ok(_) => {
                hbb_common::log::info!("Android privacy mode enabled");
            }
            Err(e) => {
                hbb_common::log::error!("Failed to enable Android privacy mode: {}", e);
                bail!("Failed to enable privacy mode: {}", e);
            }
        }

        CONN_ID.store(conn_id, Ordering::SeqCst);
        Ok(true)
    }

    fn turn_off_privacy(
        &mut self,
        conn_id: i32,
        _state: Option<super::PrivacyModeState>,
    ) -> ResultType<()> {
        self.check_off_conn_id(conn_id)?;

        // Request Flutter to hide black screen overlay
        match scrap::android::call_main_service_set_by_name(
            "toggle_privacy_mode",
            Some("false"),
            None,
        ) {
            Ok(_) => {
                hbb_common::log::info!("Android privacy mode disabled");
            }
            Err(e) => {
                hbb_common::log::error!("Failed to disable Android privacy mode: {}", e);
                // Don't bail here, still reset conn_id
            }
        }

        CONN_ID.store(super::INVALID_PRIVACY_MODE_CONN_ID, Ordering::SeqCst);
        Ok(())
    }

    fn pre_conn_id(&self) -> i32 {
        CONN_ID.load(Ordering::SeqCst)
    }

    fn get_impl_key(&self) -> &str {
        &self.impl_key
    }
}

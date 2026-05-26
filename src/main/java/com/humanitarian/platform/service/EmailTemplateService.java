package com.humanitarian.platform.service;

import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
public class EmailTemplateService {

    /**
     * Generate password reset email HTML
     */
    public String generatePasswordResetEmail(String fullName, String email, String resetCode) {
        return getPasswordResetTemplate()
                .replace("${fullName}", fullName)
                .replace("${email}", email)
                .replace("${resetCode}", resetCode);
    }

    /**
     * Generate verification email HTML
     */
    public String generateVerificationEmail(String fullName, String verificationCode, String role) {
        String roleSpecificMessage = getRoleSpecificMessage(role);
        return getVerificationTemplate()
                .replace("${fullName}", fullName)
                .replace("${verificationCode}", verificationCode)
                .replace("${role}", role)
                .replace("${roleSpecificMessage}", roleSpecificMessage);
    }

    /**
     * Generate approval email HTML
     */
    public String generateApprovalEmail(String fullName, String role) {
        return getApprovalTemplate()
                .replace("${fullName}", fullName)
                .replace("${role}", role);
    }

    /**
     * Generate rejection email HTML
     */
    public String generateRejectionEmail(String fullName) {
        return getRejectionTemplate()
                .replace("${fullName}", fullName);
    }

    // ─── TEMPLATE METHODS ───────────────────────────────────────

    private String getPasswordResetTemplate() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>Reset Your Password</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5; color: #333; line-height: 1.6; }
                        .email-container { max-width: 600px; margin: 20px auto; background: white; border-radius: 8px; box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1); overflow: hidden; }
                        .email-header { background: linear-gradient(135deg, #1d4ed8 0%, #059669 100%); padding: 40px 20px; text-align: center; color: white; }
                        .email-header h1 { font-size: 28px; font-weight: 800; margin: 10px 0; }
                        .email-content { padding: 40px 30px; }
                        .code-section { background: #f0fdf4; border: 2px solid #059669; border-radius: 8px; padding: 30px; text-align: center; margin: 30px 0; }
                        .code-label { font-size: 12px; color: #059669; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 10px; }
                        .code-display { font-family: 'Courier New', monospace; font-size: 32px; font-weight: 700; color: #1d4ed8; letter-spacing: 6px; word-spacing: 10px; }
                        .expiry-notice { font-size: 13px; color: #666; margin-top: 15px; font-style: italic; }
                        .divider { height: 1px; background: #e2e8f0; margin: 25px 0; }
                        .email-footer { background: #1e293b; color: #94a3b8; padding: 30px; text-align: center; font-size: 12px; }
                        @media (max-width: 600px) {
                            .email-content { padding: 25px 15px; }
                            .code-display { font-size: 24px; letter-spacing: 3px; word-spacing: 5px; }
                        }
                    </style>
                </head>
                <body>
                    <div class="email-container">
                        <div class="email-header">
                            <h1>Reset Your Password</h1>
                            <p style="font-size: 14px; opacity: 0.9;">Secure your account in seconds</p>
                        </div>
                        <div class="email-content">
                            <p class="greeting">Hello <strong>${fullName}</strong>,</p>
                            <p>We received a request to reset the password for your Nidaa account. If you didn't make this request, you can safely ignore this email.</p>
                            <div class="code-section">
                                <div class="code-label">🔐 Your Reset Code</div>
                                <div class="code-display">${resetCode}</div>
                                <div class="expiry-notice">⏱️ This code expires in 15 minutes</div>
                            </div>
                            <p><strong>How to Reset Your Password:</strong></p>
                            <ol style="margin: 10px 0 0 20px; font-size: 14px; color: #333;">
                                <li>Go to the password reset page on Nidaa</li>
                                <li>Enter your email: <strong>${email}</strong></li>
                                <li>Paste the code above</li>
                                <li>Enter your new password (minimum 6 characters)</li>
                                <li>Click "Reset Password"</li>
                            </ol>
                            <div class="divider"></div>
                            <p style="font-size: 13px; color: #666;">
                                <strong>Having trouble?</strong> Contact us at <a href="mailto:supp0rtnidaa@yandex.ru" style="color: #1d4ed8; text-decoration: none;">supp0rtnidaa@yandex.ru</a>
                            </p>
                        </div>
                        <div class="email-footer">
                            <p>© 2024 Nidaa — Humanitarian Platform</p>
                            <p style="margin-top: 10px; font-size: 11px;">Empowering communities, one act of kindness at a time</p>
                        </div>
                    </div>
                </body>
                </html>
                """;
    }

    private String getVerificationTemplate() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>Verify Your Email</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5; color: #333; }
                        .email-container { max-width: 600px; margin: 20px auto; background: white; border-radius: 8px; box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1); overflow: hidden; }
                        .email-header { background: linear-gradient(135deg, #1d4ed8 0%, #059669 100%); padding: 40px 20px; text-align: center; color: white; }
                        .email-header h1 { font-size: 28px; font-weight: 800; margin: 10px 0; }
                        .email-content { padding: 40px 30px; }
                        .verification-card { background: linear-gradient(135deg, #f0fdf4 0%, #eff6ff 100%); border: 2px solid #059669; border-radius: 8px; padding: 30px; text-align: center; margin: 30px 0; }
                        .code-box { background: white; border: 2px dashed #059669; border-radius: 6px; padding: 20px; margin: 15px 0; }
                        .code-label { font-size: 12px; color: #059669; font-weight: 700; text-transform: uppercase; margin-bottom: 10px; }
                        .code-display { font-family: 'Courier New', monospace; font-size: 28px; font-weight: 700; color: #1d4ed8; letter-spacing: 4px; }
                        .role-badge { display: inline-block; background: #eff6ff; border: 1px solid #1d4ed8; color: #1d4ed8; padding: 8px 16px; border-radius: 20px; font-size: 13px; font-weight: 600; margin: 10px 0; }
                        .welcome-message { background: #f0fdf4; border-left: 4px solid #059669; padding: 20px; border-radius: 4px; margin: 25px 0; font-size: 14px; color: #166534; }
                        .email-footer { background: #1e293b; color: #94a3b8; padding: 30px; text-align: center; font-size: 12px; }
                    </style>
                </head>
                <body>
                    <div class="email-container">
                        <div class="email-header">
                            <h1>🎉 Welcome to Nidaa!</h1>
                            <p style="font-size: 14px; opacity: 0.9;">Complete your registration</p>
                        </div>
                        <div class="email-content">
                            <p style="font-size: 16px; margin-bottom: 20px;">Hi <strong>${fullName}</strong>,</p>
                            <p>Thank you for joining Nidaa! To complete your registration, please verify your email address.</p>
                            <div class="verification-card">
                                <h2 style="font-size: 18px; color: #1d4ed8; margin-bottom: 15px;">📧 Verify Your Email</h2>
                                <p style="font-size: 13px; color: #666; margin-bottom: 15px;">Enter this code to confirm your email:</p>
                                <div class="code-box">
                                    <div class="code-label">Verification Code</div>
                                    <div class="code-display">${verificationCode}</div>
                                </div>
                                <p style="font-size: 12px; color: #666; margin-top: 15px;">⏱️ This code expires in 15 minutes</p>
                            </div>
                            <p style="text-align: center; margin-bottom: 15px;">
                                <span class="role-badge">${role}</span>
                            </p>
                            <div class="welcome-message">
                                <strong>🌟 What's Next?</strong><br/>
                                ${roleSpecificMessage}
                            </div>
                        </div>
                        <div class="email-footer">
                            <p>© 2024 Nidaa — Humanitarian Platform</p>
                        </div>
                    </div>
                </body>
                </html>
                """;
    }

    private String getApprovalTemplate() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>Account Approved!</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5; }
                        .email-container { max-width: 600px; margin: 20px auto; background: white; border-radius: 8px; box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1); overflow: hidden; }
                        .email-header { background: linear-gradient(135deg, #059669 0%, #10b981 100%); padding: 40px 20px; text-align: center; color: white; }
                        .email-header h1 { font-size: 28px; font-weight: 800; margin: 10px 0; }
                        .email-content { padding: 40px 30px; }
                        .success-box { background: #f0fdf4; border: 2px solid #059669; border-radius: 8px; padding: 30px; text-align: center; margin: 25px 0; }
                        .success-title { font-size: 20px; font-weight: 700; color: #15803d; margin-bottom: 10px; }
                        .success-message { font-size: 15px; color: #166534; }
                        .cta-button { display: block; background: #059669; color: white; padding: 14px 32px; text-decoration: none; border-radius: 6px; font-weight: 600; text-align: center; margin: 30px 0; }
                        .email-footer { background: #1e293b; color: #94a3b8; padding: 30px; text-align: center; font-size: 12px; }
                    </style>
                </head>
                <body>
                    <div class="email-container">
                        <div class="email-header">
                            <div style="font-size: 60px; margin-bottom: 15px;">✅</div>
                            <h1>Account Approved!</h1>
                            <p style="font-size: 14px; opacity: 0.9;">Welcome to the Nidaa team</p>
                        </div>
                        <div class="email-content">
                            <p style="font-size: 16px; margin-bottom: 20px;">Hi <strong>${fullName}</strong>,</p>
                            <p>Great news! Your application as a <strong>${role}</strong> has been approved. You're now ready to make a difference in our community.</p>
                            <div class="success-box">
                                <div class="success-title">🎉 You're All Set!</div>
                                <div class="success-message">Your account is fully activated. Start helping and connecting today.</div>
                            </div>
                            <a href="http://localhost:8081/login.html" class="cta-button">→ Log In Now</a>
                        </div>
                        <div class="email-footer">
                            <p>© 2024 Nidaa — Humanitarian Platform</p>
                        </div>
                    </div>
                </body>
                </html>
                """;
    }

    private String getRejectionTemplate() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8" />
                    <title>Application Update</title>
                </head>
                <body style="font-family: Arial, sans-serif; background: #f5f5f5; margin: 0; padding: 20px;">
                    <div style="max-width: 600px; margin: 0 auto; background: white; border-radius: 8px; padding: 40px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                        <h1 style="color: #1e293b; margin-bottom: 20px;">Application Update</h1>
                        <p>Hello ${fullName},</p>
                        <p>We are unable to approve your account at this time. If you believe this is a mistake or have questions, please reach out to us.</p>
                        <p><strong>Contact:</strong> <a href="mailto:supp0rtnidaa@yandex.ru">supp0rtnidaa@yandex.ru</a></p>
                        <p>Thank you for your interest in Nidaa.</p>
                        <hr style="border: none; border-top: 1px solid #e2e8f0; margin: 30px 0;">
                        <p style="font-size: 12px; color: #666;">© 2024 Nidaa — Humanitarian Platform</p>
                    </div>
                </body>
                </html>
                """;
    }

    private String getRoleSpecificMessage(String role) {
        return switch (role.toUpperCase()) {
            case "VOLUNTEER" -> 
                "As a volunteer, you'll help people directly. Browse requests, connect with beneficiaries, and make a real difference in the community.";
            case "PSYCHOLOGIST" -> 
                "As a psychologist, you can provide mental health support to those in need. Access consultation requests and sessions securely.";
            case "ORGANIZATION" -> 
                "As an organization, you can coordinate large-scale humanitarian efforts and help multiple people at once.";
            case "BENEFICIARY" -> 
                "As a beneficiary, you can request help and connect with volunteers and organizations ready to support you.";
            default -> 
                "You're now part of our humanitarian community. Explore the platform and get involved.";
        };
    }
}
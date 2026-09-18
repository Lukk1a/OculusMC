"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { fetchApi } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Loader2, ArrowRight, ShieldCheck, CheckCircle2, Lock, AlertCircle, Copy } from "lucide-react";

export default function SetupPage() {
  const router = useRouter();
  const [loadingStatus, setLoadingStatus] = useState(true);
  
  const [step, setStep] = useState<0 | 1 | 2>(0); // 0 = credentials, 1 = totp, 2 = success
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const [qrCodeDataUri, setQrCodeDataUri] = useState<string>("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [totpCode, setTotpCode] = useState("");

  useEffect(() => {
    async function checkStatus() {
      try {
        const res = await fetchApi("/api/auth/setup-status");
        if (res && res.setupRequired === false) {
          router.replace("/login");
        } else {
          setLoadingStatus(false);
        }
      } catch (err: any) {
        console.error("Failed to check setup status:", err);
        setLoadingStatus(false);
      }
    }
    checkStatus();
  }, [router]);

  const handleCreateAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    if (password !== confirmPassword) {
      setError("Passwords do not match");
      return;
    }
    if (password.length < 8) {
      setError("Password must be at least 8 characters");
      return;
    }
    setError(null);
    setIsSubmitting(true);

    try {
      const data = await fetchApi("/api/auth/setup", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      });
      
      setQrCodeDataUri(data.qrPngBase64 ? `data:image/png;base64,${data.qrPngBase64}` : "");
      setRecoveryCodes(data.recoveryCodes || []);
      setStep(1);
    } catch (err: any) {
      setError(err.message || "Failed to create account");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleVerifyTotp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!totpCode || totpCode.length !== 6) {
      setError("Enter a valid 6-digit code");
      return;
    }
    setError(null);
    setIsSubmitting(true);

    try {
      await fetchApi("/api/auth/totp", {
        method: "POST",
        body: JSON.stringify({ code: totpCode }),
      });
      setStep(2);
    } catch (err: any) {
      setError(err.message || "Invalid TOTP code");
    } finally {
      setIsSubmitting(false);
    }
  };

  const copyCodes = () => {
    navigator.clipboard.writeText(recoveryCodes.join("\n"));
  };

  if (loadingStatus) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <Loader2 className="w-8 h-8 text-muted-foreground animate-spin" />
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-background p-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-xl bg-secondary border border-border mb-4 ">
            <ShieldCheck className="w-6 h-6 text-foreground" />
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">Oculus Setup</h1>
          <p className="text-sm text-muted-foreground mt-2">Initialize your admin account</p>
        </div>

        <Card className="bg-card border-border  overflow-hidden relative">
          <AnimatePresence mode="wait">
            {step === 0 && (
              <motion.div
                key="step0"
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 20 }}
                transition={{ duration: 0.2 }}
              >
                <form onSubmit={handleCreateAccount}>
                  <CardHeader>
                    <CardTitle className="text-lg">Admin Credentials</CardTitle>
                    <CardDescription>
                      Create the master administrator account for your server.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {error && (
                      <div className="flex items-center gap-2 p-3 rounded-md bg-destructive/10 border border-destructive/20 text-destructive text-sm">
                        <AlertCircle className="w-4 h-4 shrink-0" />
                        <p>{error}</p>
                      </div>
                    )}
                    <div className="space-y-2">
                      <Label htmlFor="username">Username</Label>
                      <Input
                        id="username"
                        autoFocus
                        required
                        className="bg-background"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                        placeholder="admin"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="password">Password</Label>
                      <Input
                        id="password"
                        type="password"
                        required
                        className="bg-background"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="confirmPassword">Confirm Password</Label>
                      <Input
                        id="confirmPassword"
                        type="password"
                        required
                        className="bg-background"
                        value={confirmPassword}
                        onChange={(e) => setConfirmPassword(e.target.value)}
                      />
                    </div>
                  </CardContent>
                  <CardFooter>
                    <Button 
                      type="submit" 
                      className="w-full" 
                      disabled={isSubmitting}
                    >
                      {isSubmitting ? <Loader2 className="w-4 h-4 animate-spin" /> : "Continue"}
                      {!isSubmitting && <ArrowRight className="w-4 h-4 ml-2" />}
                    </Button>
                  </CardFooter>
                </form>
              </motion.div>
            )}

            {step === 1 && (
              <motion.div
                key="step1"
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 20 }}
                transition={{ duration: 0.2 }}
              >
                <form onSubmit={handleVerifyTotp}>
                  <CardHeader>
                    <CardTitle className="text-lg">Two-Factor Authentication</CardTitle>
                    <CardDescription>
                      Scan the QR code with your authenticator app to secure your account.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-6">
                    {error && (
                      <div className="flex items-center gap-2 p-3 rounded-md bg-destructive/10 border border-destructive/20 text-destructive text-sm">
                        <AlertCircle className="w-4 h-4 shrink-0" />
                        <p>{error}</p>
                      </div>
                    )}
                    
                    <div className="flex flex-col md:flex-row gap-6">
                      <div className="flex-shrink-0 flex justify-center p-3 bg-white rounded-xl border border-border">
                        {qrCodeDataUri ? (
                          <img 
                            src={qrCodeDataUri} 
                            alt="TOTP QR Code" 
                            className="w-40 h-40 rounded-md"
                          />
                        ) : (
                          <div className="w-40 h-40 flex items-center justify-center text-muted-foreground bg-secondary rounded-md">
                            <Lock className="w-8 h-8" />
                          </div>
                        )}
                      </div>
                      
                      <div className="flex flex-col gap-2 flex-grow min-w-0">
                        <div className="flex items-center justify-between">
                          <Label className="text-xs uppercase tracking-wider text-muted-foreground">Recovery Codes</Label>
                          <button
                            type="button"
                            onClick={copyCodes}
                            className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1 transition-colors"
                          >
                            <Copy className="w-3 h-3" /> Copy
                          </button>
                        </div>
                        <div className="grid grid-cols-2 gap-2 p-3 bg-background border border-border rounded-md font-mono text-xs">
                          {recoveryCodes.length > 0 ? (
                            recoveryCodes.map((code, i) => (
                              <div key={i} className="text-foreground tracking-tight">
                                {code}
                              </div>
                            ))
                          ) : (
                            <div className="col-span-2 text-center text-muted-foreground py-2">Generating codes...</div>
                          )}
                        </div>
                      </div>
                    </div>

                    <div className="space-y-2">
                      <Label htmlFor="totp">Verification Code</Label>
                      <Input
                        id="totp"
                        autoFocus
                        required
                        autoComplete="one-time-code"
                        placeholder="000000"
                        className="bg-background text-center text-lg tracking-[0.5em] font-mono tabular-nums h-12"
                        value={totpCode}
                        onChange={(e) => setTotpCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                      />
                    </div>
                  </CardContent>
                  <CardFooter>
                    <Button 
                      type="submit" 
                      className="w-full" 
                      disabled={isSubmitting || totpCode.length < 6}
                    >
                      {isSubmitting ? <Loader2 className="w-4 h-4 animate-spin" /> : "Verify & Complete"}
                    </Button>
                  </CardFooter>
                </form>
              </motion.div>
            )}

            {step === 2 && (
              <motion.div
                key="step2"
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={{ duration: 0.3, type: "spring" }}
              >
                <div className="py-12 flex flex-col items-center text-center px-6">
                  <div className="w-16 h-16 bg-emerald-500/10 rounded-full flex items-center justify-center mb-4 border border-emerald-500/20">
                    <CheckCircle2 className="w-8 h-8 text-emerald-500" />
                  </div>
                  <h2 className="text-xl font-medium text-foreground mb-2">Setup Complete</h2>
                  <p className="text-muted-foreground text-sm mb-8">
                    Your master admin account has been created and secured.
                  </p>
                  <Button 
                    onClick={() => router.push("/login")}
                    className="px-8"
                  >
                    Go to Login
                  </Button>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </Card>
      </div>
    </div>
  );
}

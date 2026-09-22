"use client";

import { useState, useEffect, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Shield, KeyRound, AlertCircle, Loader2, ArrowRight } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { fetchApi, setToken, refreshAccessToken } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";

type LoginStep = "credentials" | "totp";

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const returnUrl = searchParams?.get("returnUrl") || "/dashboard";

  const [step, setStep] = useState<LoginStep>("credentials");
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  // Form State
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [totpCode, setTotpCode] = useState("");

  useEffect(() => {
    // Check if we already have a valid session via refresh token
    refreshAccessToken().then((token) => {
      if (token) {
        router.push(returnUrl);
      }
    });
  }, [router, returnUrl]);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setIsLoading(true);

    try {
      const response = await fetchApi("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      });

      if (response.totpRequired) {
        setStep("totp");
      } else if (response.accessToken) {
        setToken(response.accessToken);
        router.push(returnUrl);
      } else {
        setStep("totp");
      }
    } catch (err: any) {
      setError(err.message || "Invalid credentials");
    } finally {
      setIsLoading(false);
    }
  };

  const handleVerifyTotp = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setIsLoading(true);

    try {
      const response = await fetchApi("/api/auth/totp", {
        method: "POST",
        body: JSON.stringify({ code: totpCode }),
      });

      if (response.accessToken) {
        setToken(response.accessToken);
      }

      router.push(returnUrl);
    } catch (err: any) {
      setError(err.message || "Invalid authenticator code");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-background p-4 relative overflow-hidden">
      <div className="w-full max-w-sm relative z-10">
        <div className="mb-8 text-center flex flex-col items-center">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-xl bg-secondary border border-border mb-4 ">
            {step === "credentials" ? (
              <Shield className="w-6 h-6 text-foreground" />
            ) : (
              <KeyRound className="w-6 h-6 text-foreground" />
            )}
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            Oculus
          </h1>
          <p className="text-sm text-muted-foreground mt-1">
            {step === "credentials"
              ? "Server Operations Console"
              : "Two-Factor Authentication"}
          </p>
        </div>

        <Card className="bg-card border-border  overflow-hidden relative">
          <AnimatePresence mode="wait">
            {step === "credentials" && (
              <motion.div
                key="credentials"
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 20 }}
                transition={{ duration: 0.2 }}
              >
                <form onSubmit={handleLogin}>
                  <CardHeader className="pb-4">
                    <CardTitle className="text-lg">Sign In</CardTitle>
                    <CardDescription>
                      Enter your credentials to continue.
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
                        autoComplete="username"
                      />
                    </div>
                    <div className="space-y-2">
                      <div className="flex items-center justify-between">
                        <Label htmlFor="password">Password</Label>
                      </div>
                      <Input
                        id="password"
                        type="password"
                        required
                        className="bg-background"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        placeholder="••••••••"
                        autoComplete="current-password"
                      />
                    </div>
                  </CardContent>
                  <CardFooter>
                    <Button 
                      type="submit" 
                      className="w-full" 
                      disabled={isLoading}
                    >
                      {isLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : "Sign In"}
                      {!isLoading && <ArrowRight className="w-4 h-4 ml-2" />}
                    </Button>
                  </CardFooter>
                </form>
              </motion.div>
            )}

            {step === "totp" && (
              <motion.div
                key="totp"
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 20 }}
                transition={{ duration: 0.2 }}
              >
                <form onSubmit={handleVerifyTotp}>
                  <CardHeader className="pb-4">
                    <CardTitle className="text-lg">Verify Identity</CardTitle>
                    <CardDescription>
                      Enter the 6-digit code from your authenticator app.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {error && (
                      <div className="flex items-center gap-2 p-3 rounded-md bg-destructive/10 border border-destructive/20 text-destructive text-sm">
                        <AlertCircle className="w-4 h-4 shrink-0" />
                        <p>{error}</p>
                      </div>
                    )}
                    <div className="space-y-2 pt-2">
                      <Label htmlFor="totp">Verification Code</Label>
                      <Input
                        id="totp"
                        autoFocus
                        required
                        autoComplete="one-time-code"
                        placeholder="000000"
                        className="bg-background text-center text-lg tracking-[0.5em] font-mono tabular-nums h-12"
                        maxLength={6}
                        value={totpCode}
                        onChange={(e) => setTotpCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                      />
                    </div>
                  </CardContent>
                  <CardFooter className="flex-col gap-2">
                    <Button 
                      type="submit" 
                      className="w-full" 
                      disabled={isLoading || totpCode.length !== 6}
                    >
                      {isLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : "Verify Code"}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      onClick={() => {
                        setStep("credentials");
                        setTotpCode("");
                        setError(null);
                      }}
                      className="w-full text-muted-foreground"
                    >
                      Back to Login
                    </Button>
                  </CardFooter>
                </form>
              </motion.div>
            )}
          </AnimatePresence>
        </Card>
        <p className="text-xs text-center text-muted-foreground mt-6">
          Protected access — operators only
        </p>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen bg-background flex items-center justify-center">
        <Loader2 className="w-8 h-8 animate-spin text-muted-foreground" />
      </div>
    }>
      <LoginForm />
    </Suspense>
  );
}


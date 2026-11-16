import React, { useState } from 'react';
import splitIllustration from '../assets/split-illustration.png';

function Login(){
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [message, setMessage] = useState('');

    function handleSubmit(event) {
    event.preventDefault();
    setMessage("imma get to this later lol!");
    console.log("Login form submitted.");
}   

    return (
        <div className="login-page">
            <div className="login-branding">
                <svg
                className="login-wave"
                viewBox="0 0 100 800"
                preserveAspectRatio="none"
                aria-hidden="true"
                >
                <path
                    d="M100 0 H55 C5 130 95 250 55 400 C15 550 95 670 55 800 H100 Z"
                    fill="#fffef8"
                />
                </svg>

                <h2>Split Together</h2>
                <p>Track, split, and settle expenses with friends.</p>
                <img
                src={splitIllustration}
                alt="Friends splitting expenses"
                className="login-illustration"
                />
            </div>
            <div className="login-content">
            <div className="login-form-container">
                <h1>Welcome back!</h1>
                <p className="login-subtitle">Login to split together.</p>

                
                <form onSubmit={handleSubmit} className="login-form">
                {/* Email */}
                <div className="form-field">
                    <label htmlFor="email">Email</label>

                    <div className="input-wrapper">
                    <svg
                        className="input-icon"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                    >
                        <rect x="3" y="5" width="18" height="14" rx="2" />
                        <path d="m3 7 9 6 9-6" />
                    </svg>

                    <input
                        id="email"
                        type="email"
                        placeholder="Enter your email"
                        autoComplete="email"
                        value={email}
                        required
                        onChange={(event) => setEmail(event.target.value)}
                    />
                    </div>
                </div>

                {/* Password */}
                <div className="form-field">
                    <label htmlFor="password">Password</label>

                    <div className="input-wrapper">
                    <svg
                        className="input-icon"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                    >
                        <rect x="5" y="10" width="14" height="11" rx="2" />
                        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
                    </svg>

                    <input
                        id="password"
                        type={showPassword ? "text" : "password"}
                        placeholder="Enter your password"
                        autoComplete="current-password"
                        value={password}
                        required
                        onChange={(event) => setPassword(event.target.value)}
                    />

                    <button
                        type="button"
                        className="password-toggle"
                        onClick={() => setShowPassword(prev => !prev)}
                        aria-label={showPassword ? "Hide password" : "Show password"}
                        aria-pressed={showPassword}
                    >
                        {showPassword ? (
                        <svg
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="1.8"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            aria-hidden="true"
                        >
                            <path d="M3 3l18 18" />
                            <path d="M10.6 5.2A10.7 10.7 0 0 1 12 5c5 0 9 7 9 7a16 16 0 0 1-3.1 3.6" />
                            <path d="M6.2 6.2C3.8 8 3 12 3 12s4 7 9 7c1.6 0 3.1-.5 4.3-1.3" />
                        </svg>
                        ) : (
                        <svg
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="1.8"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            aria-hidden="true"
                        >
                            <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
                            <circle cx="12" cy="12" r="3" />
                        </svg>
                        )}
                    </button>
                    </div>
                </div>

                <a href="/forgot-password" className="forgot-password">
                    Forgot Password?
                </a>

                <button type="submit" className="login-button">
                    Login
                </button>
                </form>

                <div className="signup-divider" aria-hidden="true"></div>
                <p className="signup-prompt">
                Don't have an account?{" "}
                <a href="/signup" className="signup-link">
                    Sign up!
                </a>
                </p>

                {message && <p className="login-message">{message}</p>}
            </div>
            </div>


        </div>
    )
}  

export default Login;
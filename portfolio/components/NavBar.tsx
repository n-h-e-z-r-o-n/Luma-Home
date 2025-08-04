import Link from 'next/link'
import {ReactNode} from 'react';
import "../app/globals.css";
import {Geist} from 'next/font/google'

const geist = Geist({
    subsets: ['latin'],
    weight: ['400', '700'],
    display: 'swap',
});

export default function navBar() {
    return (
        <div className={"navbar"}>
            <div><Link className={"logo"} href="/">n-h-e-z-r-o-n</Link></div>
            <div className={"navBar-links"}>
                <div><Link href="/about/">About Us</Link></div>
                <div><Link href="/about/">Customer</Link></div>
                <div><Link href="/about">Contact Us</Link></div>
            </div>

        </div>

    );
}
import { ReactNode } from 'react';
import "../app/globals.css";
import Image from "next/image";

export default function HomeSection1() {
    return (
            <div className={"section1"}>
                <div className={"info_section"}>
                    <>
                    <p>Hey i'm  Hezron</p>
                    <div> I'M A SOFTWARE  </div>
                    <div> DEVELOPER</div>
                    <p>I specialize in building robust web applications, crafting elegant UIs, and writing clean, maintainable code. Passionate about performance, accessibility, and seamless user experience.</p>

                    </>
                </div>
                <Image
                  className={"user_image"}
                  src="/images/me.png" // or a full remote URL if allowed in next.config.js
                  width={500}
                  height={800}
                  sizes="(max-width: 768px) 100vw, (max-width: 1200px) 50vw, 33vw"
                  alt=""
                />
            </div>
    );
}
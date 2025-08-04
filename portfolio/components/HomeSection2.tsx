import { ReactNode } from 'react';
import "../app/globals.css";
import Image from "next/image";

export default function HomeSection2() {
    return (
            <div className={"section1"}>
                <div className={"info_section"}>
                    <>
                    <p>Hey i'm  Hezron</p>
                    <div className={"Job-title"}> I'M A SOFTWARE  </div>
                    <div className={"Job-title"}> DEVELOPER</div>
                    <p className={"Job-summary"}>I specialize in building robust web applications, crafting elegant UIs, and writing clean, maintainable code. Passionate about performance, accessibility, and seamless user experience.</p>

                    </>
                </div>
                <div className={"image-container"}>
                      <Image
                        className={"user_image"}
                         src="/images/me.png" // or a full remote URL if allowed in next.config.js
                         fill
                         alt=""
                    />
                </div>

            </div>
    );
}
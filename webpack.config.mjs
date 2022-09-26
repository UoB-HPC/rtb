import ScalaJSConfig from './scalajs.webpack.config.js';

import {merge} from 'webpack-merge';

import remarkParse from 'remark-parse'
import remarkRehype from 'remark-rehype'
import remarkTOC from "remark-toc";

import rehypeAutolinkHeadings from "rehype-autolink-headings";
import rehypeStringify from 'rehype-stringify'
import rehypeSlug from 'rehype-slug'

var local = {
    devtool: false,
    performance: {
        // See https://github.com/scalacenter/scalajs-bundler/pull/408
        // and also https://github.com/scalacenter/scalajs-bundler/issues/350
        hints: false
    },
    module: {
        rules: [
            {
                test: /\.css$/,
                use: ['style-loader', 'css-loader'],
                type: 'javascript/auto',
            },
            {
                test: /\.(eot|ttf|woff(2)?|svg|png|glb|jpeg|jpg|mp4|jsn)$/,
                type: 'asset/resource',
                generator: {
                    filename: 'static/[hash][ext][query]'
                }
                // use: 'file-loader',
            },
            {
                test: /\.md$/,
                use: [
                    {
                        loader: "html-loader",
                    },
                    {
                        loader: "remark-loader",
                        options: {
                            remarkOptions: {
                                plugins: [
                                    remarkParse, remarkTOC, remarkRehype,//
                                    rehypeSlug,   rehypeStringify],
                            },
                        },
                    },
                ],
            }
        ]
    }
};

export default merge(ScalaJSConfig, local)

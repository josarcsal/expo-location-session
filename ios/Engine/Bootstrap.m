#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

@interface ELSBootstrapLoader : NSObject
@end

@implementation ELSBootstrapLoader

+ (void)load {
  [[NSNotificationCenter defaultCenter] addObserverForName:UIApplicationDidFinishLaunchingNotification
                                                    object:nil
                                                     queue:nil
                                                usingBlock:^(NSNotification *note) {
    Class bridge = NSClassFromString(@"ELSBootstrap");
    SEL selector = NSSelectorFromString(@"didFinishLaunching:");
    if (![bridge respondsToSelector:selector]) {
      NSLog(@"[expo-location-session] ELSBootstrap bridge not found");
      return;
    }
    NSNumber *locationLaunch = @(note.userInfo[UIApplicationLaunchOptionsLocationKey] != nil);
    ((void (*)(id, SEL, NSNumber *))[bridge methodForSelector:selector])(bridge, selector, locationLaunch);
  }];
}

@end

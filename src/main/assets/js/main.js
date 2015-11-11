(function () {
    'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
    'ngRoute',
    'ngCookies',
    'ngAnimate',
    'ngMaterial',
    'slicebox.utils',
    'slicebox.directives',
    'slicebox.login',
    'slicebox.import',
    'slicebox.home',
    'slicebox.anonymization',
    'slicebox.transactions',
    'slicebox.log',
    'slicebox.adminPacs',
    'slicebox.adminForwarding',
    'slicebox.adminWatchDirectories',
    'slicebox.adminBoxes',
    'slicebox.adminUsers',
    'slicebox.adminSeriesTypes'
])

.config(function($locationProvider, $routeProvider, $mdThemingProvider, $animateProvider, $filterProvider, $httpProvider) {
    $locationProvider.html5Mode(true);
    $routeProvider.otherwise({redirectTo: '/'});

    $mdThemingProvider.theme('default')
        .primaryPalette('blue-grey', {
            'default': '600',
        })
        .accentPalette('pink')
        .warnPalette('red');

    $mdThemingProvider.theme('redTheme')
        .primaryPalette('red');

    // Register filters
    $filterProvider.register('hexvalue', function() {
        return function(intValue, length) {
            var returnValue = intValue;

            if (!length) {
                length = 4;
            }

            if (angular.isDefined(intValue) && angular.isNumber(intValue) && intValue !== 0) {
                returnValue = intValue.toString(16);

                while (returnValue.length < length) {
                    returnValue = '0' + returnValue;
                }

                returnValue = returnValue.toUpperCase();
            }

            return returnValue;
        };
    });

    $httpProvider.interceptors.push(function($q, $location) {
        return {
            'responseError': function(rejection) {
                if (rejection.status === 401) {
                    $location.path('/login');
                } else {
                    return $q.reject(rejection);
                }
            }
        };
    });
})

.filter('prettyPatientName', function () {
    return function (text) {
        return text ? text.replace(new RegExp('\\^', 'g'), ' ') : '';
    };
})

.run(function ($rootScope, $location, userService) { 
    $rootScope.$on('$locationChangeStart', function (event, next, current) {
        // redirect to login page if not logged in
        userService.currentUserPromise.then(function () {}, function () {
            if ($location.path() !== '/login') {
                $rootScope.requestedPage = current;
                $location.path('/login');
            }
        });
    });
})

.controller('SliceboxCtrl', function($scope, $location, $mdSidenav, userService) {

    $scope.uiState = {};
    
    userService.updateCurrentUser();

    $scope.logout = function() {
        userService.logout().finally(function() {
            userService.updateCurrentUser().finally(function () {
                $location.path('/login');
            });
        });
    };

    $scope.userSignedIn = function() {
        return userService.currentUser;
    };

    $scope.toggleLeftNav = function() {
        $mdSidenav('leftNav').toggle();
    };

    $scope.isCurrentPath = function(path) { 
        return $location.path() === path;
    };

    $scope.currentPathStartsWith = function(path) { 
        return $location.path().indexOf(path) === 0;
    };
});
